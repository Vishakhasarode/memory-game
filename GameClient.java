import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/**
 * Terminal client for the Multiplayer Memory Game.
 *
 * Usage:
 *   java GameClient <server-url> [create|join] [name] [roomId]
 *
 * Examples:
 *   java GameClient wss://your-app.onrender.com create Alice
 *   java GameClient wss://your-app.onrender.com join   Bob 1001
 *
 * If no args are given, the client runs in interactive mode.
 */
public class GameClient {

    // ── ANSI colours ──────────────────────────────────────────────────────────
    static final String RESET  = "\u001B[0m";
    static final String BOLD   = "\u001B[1m";
    static final String YELLOW = "\u001B[33m";
    static final String CYAN   = "\u001B[36m";
    static final String GREEN  = "\u001B[32m";
    static final String RED    = "\u001B[31m";
    static final String MAGENTA= "\u001B[35m";
    static final String DIM    = "\u001B[2m";

    // ── Game state ────────────────────────────────────────────────────────────
    static final String[] board    = new String[36];
    static final boolean[] revealed = new boolean[36];
    static final boolean[] matched  = new boolean[36];

    static String myName     = "";
    static String myRoom     = "";
    static boolean myTurn    = false;
    static boolean gameStarted = false;
    static int firstPick     = -1;          // client-side tracking for UX only

    // ── WebSocket raw I/O ─────────────────────────────────────────────────────
    static OutputStream wsOut;
    static InputStream  wsIn;

    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in);
        String serverUrl;
        String action = null;
        String name   = null;
        String roomId = null;

        // ── Welcome banner ────────────────────────────────────────────────────
        System.out.println();
        System.out.println(CYAN + BOLD + "╔══════════════════════════════════════════╗" + RESET);
        System.out.println(CYAN + BOLD + "║       🃏  Multiplayer Memory Game  🃏      ║" + RESET);
        System.out.println(CYAN + BOLD + "╚══════════════════════════════════════════╝" + RESET);
        System.out.println();

        // ── Server URL ────────────────────────────────────────────────────────
        if (args.length >= 1) {
            serverUrl = args[0];
            // Still allow name / action overrides from args
            if (args.length >= 2) action = args[1];
            if (args.length >= 3) name   = args[2];
            if (args.length >= 4) roomId = args[3];
        } else {
            System.out.print(CYAN + "Server URL (e.g. wss://your-app.onrender.com): " + RESET);
            serverUrl = sc.nextLine().trim();
        }

        // ── Connect ───────────────────────────────────────────────────────────
        System.out.println(DIM + "Connecting…" + RESET);
        connect(serverUrl);
        System.out.println(GREEN + "✔ Connected to " + serverUrl + RESET);
        System.out.println();

        // Start receiver thread
        Thread receiver = new Thread(GameClient::receiveLoop);
        receiver.setDaemon(true);
        receiver.start();

        // ── Step 1: Ask for name (always, unless passed as arg) ───────────────
        if (name == null || name.isEmpty()) {
            System.out.print(BOLD + "Enter your name: " + RESET);
            name = sc.nextLine().trim();
            while (name.isEmpty()) {
                System.out.print(RED + "Name cannot be empty. Enter your name: " + RESET);
                name = sc.nextLine().trim();
            }
        }
        System.out.println(GREEN + "👋 Welcome, " + name + "!" + RESET);
        System.out.println();

        // ── Step 2: Choose Create or Join (unless passed as arg) ──────────────
        if (action == null) {
            System.out.println(BOLD + "What would you like to do?" + RESET);
            System.out.println("  " + CYAN + "[1] Create" + RESET + "  — Start a new room and invite friends");
            System.out.println("  " + YELLOW + "[2] Join  " + RESET + "  — Enter a Room ID to join a friend");
            System.out.println();
            System.out.print("Choice (1 or 2): ");
            String choice = sc.nextLine().trim();
            while (!choice.equals("1") && !choice.equals("2")
                    && !choice.equalsIgnoreCase("c") && !choice.equalsIgnoreCase("j")
                    && !choice.equalsIgnoreCase("create") && !choice.equalsIgnoreCase("join")) {
                System.out.print(RED + "Please enter 1 (Create) or 2 (Join): " + RESET);
                choice = sc.nextLine().trim();
            }
            action = (choice.equals("1") || choice.equalsIgnoreCase("c") || choice.equalsIgnoreCase("create"))
                     ? "create" : "join";
        }

        // ── Step 3: If joining, ask for Room ID ───────────────────────────────
        if (action.equalsIgnoreCase("join") && (roomId == null || roomId.isEmpty())) {
            System.out.println();
            System.out.print(YELLOW + "Enter Room ID: " + RESET);
            roomId = sc.nextLine().trim();
            while (roomId.isEmpty()) {
                System.out.print(RED + "Room ID cannot be empty. Enter Room ID: " + RESET);
                roomId = sc.nextLine().trim();
            }
        }

        // ── Send to server ────────────────────────────────────────────────────
        myName = name;
        System.out.println();
        if (action.equalsIgnoreCase("join")) {
            System.out.println(DIM + "Joining room " + roomId + "…" + RESET);
            wsSend("JOIN:" + roomId + ":" + name);
        } else {
            System.out.println(DIM + "Creating a new room…" + RESET);
            wsSend("CREATE:" + name);
        }

        // ── Input loop ────────────────────────────────────────────────────────
        while (true) {
            String line = sc.nextLine().trim();
            if (line.isEmpty()) continue;

            if (line.equalsIgnoreCase("quit") || line.equalsIgnoreCase("exit")) {
                System.out.println(DIM + "Disconnecting…" + RESET);
                System.exit(0);
            }

            if (!gameStarted) {
                System.out.println(DIM + "(Waiting for game to start…)" + RESET);
                continue;
            }

            if (!myTurn) {
                System.out.println(DIM + "(Not your turn)" + RESET);
                continue;
            }

            try {
                int idx = Integer.parseInt(line);
                if (idx < 0 || idx > 35) { System.out.println(RED + "Index 0–35 only" + RESET); continue; }
                if (matched[idx])         { System.out.println(DIM + "Already matched" + RESET); continue; }
                if (revealed[idx])        { System.out.println(DIM + "Already revealed" + RESET); continue; }
                wsSend("PICK:" + idx);
            } catch (NumberFormatException e) {
                System.out.println(DIM + "Enter a card index (0–35) or 'quit'" + RESET);
            }
        }
    }

    // ── WebSocket handshake ───────────────────────────────────────────────────
    static void connect(String url) throws Exception {
        // Accept both ws:// and wss://
        boolean tls  = url.startsWith("wss://");
        String  host;
        int     port;
        String  path = "/";

        String stripped = url.replaceFirst("wss?://", "");
        int slashIdx = stripped.indexOf('/');
        if (slashIdx != -1) { path = stripped.substring(slashIdx); stripped = stripped.substring(0, slashIdx); }
        int colonIdx = stripped.lastIndexOf(':');
        if (colonIdx != -1) {
            host = stripped.substring(0, colonIdx);
            port = Integer.parseInt(stripped.substring(colonIdx + 1));
        } else {
            host = stripped;
            port = tls ? 443 : 80;
        }

        Socket socket;
        if (tls) {
            socket = javax.net.ssl.SSLSocketFactory.getDefault().createSocket(host, port);
        } else {
            socket = new Socket(host, port);
        }

        wsOut = socket.getOutputStream();
        wsIn  = socket.getInputStream();

        // WebSocket handshake
        byte[] keyBytes = new byte[16];
        new SecureRandom().nextBytes(keyBytes);
        String wsKey = Base64.getEncoder().encodeToString(keyBytes);

        String handshake =
            "GET " + path + " HTTP/1.1\r\n" +
            "Host: " + host + "\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: " + wsKey + "\r\n" +
            "Sec-WebSocket-Version: 13\r\n\r\n";
        wsOut.write(handshake.getBytes(StandardCharsets.UTF_8));
        wsOut.flush();

        // Read response headers
        StringBuilder sb = new StringBuilder();
        int prev = -1;
        while (true) {
            int c = wsIn.read();
            if (c < 0) throw new IOException("Server closed during handshake");
            sb.append((char) c);
            if (prev == '\r' && c == '\n' && sb.toString().endsWith("\r\n\r\n")) break;
            prev = c;
        }
        if (!sb.toString().contains("101")) throw new IOException("WebSocket upgrade failed:\n" + sb);
    }

    // ── Receive loop (runs on daemon thread) ──────────────────────────────────
    static void receiveLoop() {
        try {
            while (true) {
                String msg = wsRead();
                if (msg == null) { System.out.println(RED + "\nServer disconnected." + RESET); System.exit(1); }
                handleMessage(msg.trim());
            }
        } catch (Exception e) {
            System.out.println(RED + "\nConnection error: " + e.getMessage() + RESET);
            System.exit(1);
        }
    }

    // ── Message dispatcher ────────────────────────────────────────────────────
    static void handleMessage(String msg) {
        if (msg.startsWith("ROOM:")) {
            myRoom = msg.substring(5);
            System.out.println();
            System.out.println(CYAN + BOLD + "╔══════════════════════════════════════════╗" + RESET);
            System.out.println(CYAN + BOLD + "║          ✅  Room Created!               ║" + RESET);
            System.out.println(CYAN + BOLD + "╠══════════════════════════════════════════╣" + RESET);
            System.out.printf( CYAN + BOLD + "║   🔑 Room ID:  " + YELLOW + "%-6s" + RESET + CYAN + BOLD + "                      ║%n" + RESET, myRoom);
            System.out.println(CYAN + BOLD + "╠══════════════════════════════════════════╣" + RESET);
            System.out.println(CYAN + BOLD + "║  📤 Share this Room ID with your friends ║" + RESET);
            System.out.println(CYAN + BOLD + "║     so they can join your game!          ║" + RESET);
            System.out.println(CYAN + BOLD + "╠══════════════════════════════════════════╣" + RESET);
            System.out.println(CYAN + BOLD + "║  ⏳ Waiting for a player to join…       ║" + RESET);
            System.out.println(CYAN + BOLD + "╚══════════════════════════════════════════╝" + RESET);
            System.out.println();

        } else if (msg.startsWith("PLAYERS:")) {
            String list = msg.substring(8);
            System.out.println(MAGENTA + BOLD + "👥 Players in room: " + list + RESET);
            if (!myRoom.isEmpty()) {
                // Reminder of Room ID for creator
                System.out.println(DIM + "   (Room ID: " + myRoom + " — waiting for game to start)" + RESET);
            } else {
                System.out.println(GREEN + "   ✔ Joined room successfully! Waiting for game to start…" + RESET);
            }

        } else if (msg.equals("START")) {
            gameStarted = true;
            System.out.println("\n" + GREEN + BOLD + "🎮 Game started!" + RESET);
            printHelp();

        } else if (msg.startsWith("TURN:")) {
            String who = msg.substring(5);
            myTurn = who.equals(myName);
            if (myTurn) {
                System.out.println("\n" + YELLOW + BOLD + "⭐ Your turn! Pick a card (0–35):" + RESET);
                printBoard();
                System.out.print("> ");
            } else {
                System.out.println("\n" + DIM + "🕐 " + who + "'s turn…" + RESET);
            }

        } else if (msg.startsWith("FLIP:")) {
            String[] p = msg.split(":");
            int idx = Integer.parseInt(p[1]);
            revealed[idx] = true;
            board[idx]    = p[2];
            System.out.println("  Flipped card " + BOLD + idx + RESET + " → " + YELLOW + p[2] + RESET);
            if (myTurn) printBoard();

        } else if (msg.startsWith("MATCH:")) {
            String[] p = msg.split(":");
            int a = Integer.parseInt(p[1]), b = Integer.parseInt(p[2]);
            matched[a] = matched[b] = true;
            System.out.println(GREEN + "✔ MATCH! " + board[a] + " × " + board[b] + RESET);

        } else if (msg.startsWith("NOMATCH:")) {
            System.out.println(RED + "✘ No match — hiding cards in 1.2s…" + RESET);

        } else if (msg.startsWith("HIDE:")) {
            String[] p = msg.split(":");
            int a = Integer.parseInt(p[1]), b = Integer.parseInt(p[2]);
            revealed[a] = revealed[b] = false;
            board[a] = board[b] = null;

        } else if (msg.startsWith("SCORE")) {
            // SCORE:Alice:3:Bob:1
            String[] p = msg.split(":");
            System.out.print(CYAN + "📊 Scores — ");
            for (int i = 1; i + 1 < p.length; i += 2) {
                System.out.print(p[i] + ": " + p[i+1] + "  ");
            }
            System.out.println(RESET);

        } else if (msg.startsWith("WINNER:")) {
            String winner = msg.substring(7);
            System.out.println("\n" + YELLOW + BOLD);
            System.out.println("╔══════════════════════════════╗");
            System.out.printf( "║  🏆 Winner: %-16s  ║%n", winner);
            System.out.println("╚══════════════════════════════╝");
            System.out.println(RESET);
            System.out.println(DIM + "(Type 'quit' to exit)" + RESET);

        } else if (msg.startsWith("ERROR:")) {
            System.out.println(RED + "⚠ " + msg.substring(6) + RESET);
        }
    }

    // ── Board renderer ────────────────────────────────────────────────────────
    static void printBoard() {
        System.out.println();
        System.out.println(DIM + "     0    1    2    3    4    5" + RESET);
        for (int row = 0; row < 6; row++) {
            System.out.printf(DIM + "%2d  " + RESET, row * 6);
            for (int col = 0; col < 6; col++) {
                int idx = row * 6 + col;
                if (matched[idx]) {
                    System.out.print(GREEN + "[" + board[idx] + "] " + RESET);
                } else if (revealed[idx]) {
                    System.out.print(YELLOW + "[" + board[idx] + "] " + RESET);
                } else {
                    System.out.print(DIM + "[ ? ] " + RESET);
                }
            }
            System.out.println();
        }
        System.out.println();
    }

    static void printHelp() {
        System.out.println(DIM +
            "  Board is 6×6 (36 cards, indices 0–35).\n" +
            "  Type a number to flip a card. Match pairs to score!\n" +
            "  Green = matched  |  Yellow = revealed  |  ? = hidden" + RESET);
    }

    // ── WebSocket framing ─────────────────────────────────────────────────────
    static synchronized void wsSend(String msg) throws IOException {
        byte[] data = msg.getBytes(StandardCharsets.UTF_8);
        // Client must mask frames
        byte[] mask = new byte[4];
        new Random().nextBytes(mask);
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x81); // FIN + text opcode
        if (data.length < 126) {
            frame.write(0x80 | data.length); // masked bit set
        } else if (data.length < 65536) {
            frame.write(0x80 | 126);
            frame.write((data.length >> 8) & 0xFF);
            frame.write(data.length & 0xFF);
        }
        frame.write(mask);
        for (int i = 0; i < data.length; i++) {
            frame.write(data[i] ^ mask[i % 4]);
        }
        wsOut.write(frame.toByteArray());
        wsOut.flush();
    }

    static String wsRead() throws Exception {
        int b0 = wsIn.read(); if (b0 < 0) return null;
        int b1 = wsIn.read(); if (b1 < 0) return null;
        boolean masked = (b1 & 0x80) != 0;
        int len = b1 & 0x7F;
        if (len == 126) { len = (wsIn.read() << 8) | wsIn.read(); }
        else if (len == 127) { for (int i = 0; i < 8; i++) wsIn.read(); len = 0; }
        byte[] mask = new byte[4];
        if (masked) { int r = 0; while (r < 4) { int x = wsIn.read(mask, r, 4-r); if (x < 0) return null; r += x; } }
        byte[] data = new byte[len];
        int r = 0; while (r < len) { int x = wsIn.read(data, r, len-r); if (x < 0) return null; r += x; }
        if (masked) for (int i = 0; i < len; i++) data[i] ^= mask[i % 4];
        return new String(data, StandardCharsets.UTF_8);
    }
}
