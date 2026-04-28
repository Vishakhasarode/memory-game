import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.net.InetAddress;

public class GameServer {

    static final String[] SYMBOLS = {
        "★","♠","♥","♦","♣","☀","☽","☁",
        "⚡","❄","✿","♬","☮","⚓","✈","⚔","♛","⚙"
    };

    static final Map<Integer, Room> rooms = new ConcurrentHashMap<>();
    static volatile int roomCounter = 1000;

    public static void main(String[] args) throws Exception {
        int port = 8080;
        String envPort = System.getenv("PORT");
        if (envPort != null) port = Integer.parseInt(envPort.trim());

        ServerSocket ss = new ServerSocket(port,50,InetAddress.getByName("0.0.0.0"));
        System.out.println("Memory Game server on port " + port);

        while (true) {
            Socket s = ss.accept();
            new Thread(() -> {
                try { handleConnection(s); } catch (Exception ignored) {}
            }).start();
        }
    }

    static void handleConnection(Socket socket) throws Exception {
        InputStream in   = socket.getInputStream();
        OutputStream out = socket.getOutputStream();

        byte[] buf = new byte[8192];
        int n = in.read(buf);
        if (n <= 0) { socket.close(); return; }

        String req = new String(buf, 0, n, StandardCharsets.UTF_8);

        if (req.contains("Upgrade: websocket")) {
            handleWebSocket(socket, in, out, req);
        } else if (req.startsWith("GET")) {
            serveHttp(socket, out);
        } else {
            socket.close();
        }
    }

    static void serveHttp(Socket socket, OutputStream out) throws Exception {
        byte[] body;
        File f = new File("index.html");
        if (f.exists()) {
            body = Files.readAllBytes(f.toPath());
        } else {
            body = "index.html not found".getBytes(StandardCharsets.UTF_8);
        }
        String headers =
            "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/html; charset=UTF-8\r\n" +
            "Content-Length: " + body.length + "\r\n" +
            "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
        socket.close();
    }

    static void handleWebSocket(Socket socket, InputStream in, OutputStream out, String req) throws Exception {
        String key = "";
        for (String line : req.split("\r\n")) {
            if (line.startsWith("Sec-WebSocket-Key:")) key = line.split(": ")[1].trim();
        }
        String accept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1")
                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.UTF_8)));
        String response =
            "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
        out.write(response.getBytes(StandardCharsets.UTF_8));
        out.flush();

        Player player = new Player(socket, out);
        wsLoop(player, in);
    }

    static void wsLoop(Player player, InputStream in) {
        try {
            while (true) {
                String msg = wsRead(in);
                if (msg == null) break;
                dispatch(player, msg.trim());
            }
        } catch (Exception ignored) {}
        finally {
            Room room = player.room;
            if (room != null) {
                room.players.remove(player);
                if (!room.players.isEmpty()) {
                    // If creator left, assign a new creator
                    if (room.creator == player) {
                        room.creator = room.players.get(0);
                        send(room.creator, "YOU_ARE_CREATOR");
                    }
                    broadcast(room, "PLAYERS:" + playerList(room));
                    if (room.started && room.turnIndex >= room.players.size()) {
                        room.turnIndex = 0;
                        nextTurn(room);
                    }
                }
            }
        }
    }

    static synchronized void dispatch(Player player, String msg) {
        try {
            if (msg.startsWith("CREATE:")) {
                String name = msg.substring(7).trim();
                if (name.isEmpty()) { send(player, "ERROR:Name required"); return; }
                player.name = name;
                int id = roomCounter++;
                Room room = new Room(id);
                room.creator = player;   // first player is the creator
                room.players.add(player);
                player.room = room;
                rooms.put(id, room);
                send(player, "ROOM:" + id);
                send(player, "PLAYERS:" + name);
                send(player, "YOU_ARE_CREATOR");  // tell client to show Start button
                System.out.println("[" + id + "] " + name + " created room");

            } else if (msg.startsWith("JOIN:")) {
                String[] p = msg.split(":", 3);
                if (p.length < 3) { send(player, "ERROR:Invalid"); return; }
                int id;
                try { id = Integer.parseInt(p[1].trim()); }
                catch (NumberFormatException e) { send(player, "ERROR:Invalid room ID"); return; }
                String name = p[2].trim();
                if (name.isEmpty()) { send(player, "ERROR:Name required"); return; }

                Room room = rooms.get(id);
                if (room == null)             { send(player, "ERROR:Room not found"); return; }
                if (room.started)             { send(player, "ERROR:Game already started"); return; }
                if (room.players.size() >= 5) { send(player, "ERROR:Room full (max 5)"); return; }

                player.name = name;
                player.room = room;
                room.players.add(player);
                broadcast(room, "PLAYERS:" + playerList(room));
                System.out.println("[" + id + "] " + name + " joined (" + room.players.size() + " players)");

                // No auto-start — wait for creator to send START_GAME

            } else if (msg.equals("START_GAME")) {
                Room room = player.room;
                if (room == null)          { send(player, "ERROR:Not in a room"); return; }
                if (room.started)          { send(player, "ERROR:Already started"); return; }
                if (room.creator != player){ send(player, "ERROR:Only the room creator can start"); return; }
                if (room.players.size() < 2){ send(player, "ERROR:Need at least 2 players"); return; }
                startGame(room);

            } else if (msg.startsWith("PICK:")) {
                int idx;
                try { idx = Integer.parseInt(msg.substring(5).trim()); }
                catch (NumberFormatException e) { return; }
                handlePick(player, idx);
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    static void startGame(Room room) {
        room.started = true;
        room.initBoard();
        broadcast(room, "START");
        sendScores(room);
        nextTurn(room);
    }

    static void nextTurn(Room room) {
        room.firstPick = -1;
        if (room.players.isEmpty()) return;
        room.turnIndex = room.turnIndex % room.players.size();
        broadcast(room, "TURN:" + room.players.get(room.turnIndex).name);
    }

    static void handlePick(Player p, int idx) {
        Room room = p.room;
        if (room == null || !room.started || room.players.isEmpty()) return;
        Player cur = room.players.get(room.turnIndex % room.players.size());
        if (cur != p) return;
        if (idx < 0 || idx >= 36 || room.matched[idx] || room.revealed[idx] || room.waitingHide) return;

        room.revealed[idx] = true;
        broadcast(room, "FLIP:" + idx + ":" + room.board[idx]);

        if (room.firstPick == -1) {
            room.firstPick = idx;
        } else {
            int a = room.firstPick, b = idx;
            room.firstPick = -1;

            if (room.board[a].equals(room.board[b])) {
                room.matched[a] = room.matched[b] = true;
                p.score++;
                broadcast(room, "MATCH:" + a + ":" + b);
                sendScores(room);
                if (isGameOver(room)) { announceWinner(room); return; }
                nextTurn(room);
            } else {
                room.waitingHide = true;
                broadcast(room, "NOMATCH:" + a + ":" + b);
                new Thread(() -> {
                    try { Thread.sleep(1200); } catch (Exception ignored) {}
                    room.revealed[a] = false; room.revealed[b] = false;
                    room.waitingHide = false;
                    broadcast(room, "HIDE:" + a + ":" + b);
                    room.turnIndex = (room.turnIndex + 1) % room.players.size();
                    nextTurn(room);
                }).start();
            }
        }
    }

    static void sendScores(Room room) {
        StringBuilder sb = new StringBuilder("SCORE");
        for (Player p : room.players) sb.append(":").append(p.name).append(":").append(p.score);
        broadcast(room, sb.toString());
    }

    static boolean isGameOver(Room r) {
        for (boolean m : r.matched) if (!m) return false;
        return true;
    }

    static void announceWinner(Room room) {
        Player winner = Collections.max(room.players, Comparator.comparingInt(x -> x.score));
        broadcast(room, "WINNER:" + winner.name);
    }

    static String playerList(Room room) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < room.players.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(room.players.get(i).name);
        }
        return sb.toString();
    }

    static void broadcast(Room room, String msg) {
        for (Player p : new ArrayList<>(room.players)) send(p, msg);
    }

    static synchronized void send(Player p, String msg) {
        try {
            byte[] data = msg.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write(0x81);
            if (data.length < 126) { frame.write(data.length); }
            else if (data.length < 65536) { frame.write(126); frame.write((data.length>>8)&0xFF); frame.write(data.length&0xFF); }
            frame.write(data);
            p.out.write(frame.toByteArray());
            p.out.flush();
        } catch (Exception ignored) {}
    }

    static String wsRead(InputStream in) throws Exception {
        int b0 = in.read(); if (b0<0) return null;
        int b1 = in.read(); if (b1<0) return null;
        boolean masked = (b1&0x80)!=0;
        int len = b1&0x7F;
        if (len==126) { len=(in.read()<<8)|in.read(); }
        else if (len==127) { for(int i=0;i<8;i++) in.read(); len=0; }
        byte[] mask = new byte[4];
        if (masked) { int r=0; while(r<4){int x=in.read(mask,r,4-r);if(x<0)return null;r+=x;} }
        byte[] data = new byte[len];
        int r=0; while(r<len){int x=in.read(data,r,len-r);if(x<0)return null;r+=x;}
        if (masked) for(int i=0;i<len;i++) data[i]^=mask[i%4];
        return new String(data, StandardCharsets.UTF_8);
    }

    static class Player {
        String name="?"; int score=0; Socket socket; OutputStream out; Room room;
        Player(Socket s, OutputStream o) { socket=s; out=o; }
    }

    static class Room {
        int id;
        Player creator;  // the player who created the room
        List<Player> players = Collections.synchronizedList(new ArrayList<>());
        String[] board = new String[36];
        boolean[] revealed = new boolean[36];
        boolean[] matched = new boolean[36];
        int turnIndex=0, firstPick=-1;
        boolean started=false, waitingHide=false;
        Room(int id) { this.id=id; }
        void initBoard() {
            List<String> c = new ArrayList<>();
            for(String s:SYMBOLS){c.add(s);c.add(s);}
            Collections.shuffle(c);
            for(int i=0;i<36;i++) board[i]=c.get(i);
        }
    }
}
