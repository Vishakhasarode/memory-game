FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY GameServer.java .
COPY index.html .
RUN javac GameServer.java
EXPOSE 8080
CMD ["java", "GameServer"]