package com.chatvirtual.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.websocket.CloseReason;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Endpoint WebSocket para presencia, mensajes privados, archivos y señalización
 * WebRTC.
 */
@ServerEndpoint("/chat")
public class ChatServer {

    private static final Logger LOG = Logger.getLogger(ChatServer.class.getName());
    private static final int MAX_CLIENTS = Integer.getInteger("chat.maxClients", 50);
    private static final int MAX_CHUNK_BYTES = 60 * 1024;
    private static final long MAX_FILE_BYTES = Long.getLong("chat.maxFileBytes", 20L * 1024 * 1024 * 1024);
    private static final AtomicInteger CONNECTIONS = new AtomicInteger();
    private static final Map<Session, Client> CLIENTS = new ConcurrentHashMap<>();
    private static final Map<Session, Transfer> TRANSFERS = new ConcurrentHashMap<>();
    private static final Map<String, Room> ROOMS = new ConcurrentHashMap<>();

    private static final class Client {

        final String id;
        final String name;
        volatile String status = "online";

        Client(Session session, String name) {
            this.id = session.getId();
            this.name = name;
        }
    }

    private static final class Transfer {

        final Session target;
        final String fileId;
        final long size;
        volatile long received;
        volatile long forwarded;
        volatile boolean accepted;

        Transfer(Session target, String fileId, long size) {
            this.target = target;
            this.fileId = fileId;
            this.size = size;
        }
    }

    private static final class Room {

        final String id;
        final Map<String, Session> members = new ConcurrentHashMap<>();

        Room(String id) {
            this.id = id;
        }
    }

    @OnOpen
    public void onOpen(Session session) {
        // Rechaza exceso de conexiones antes de aceptar datos de aplicación.
        if (CONNECTIONS.incrementAndGet() > MAX_CLIENTS) {
            CONNECTIONS.decrementAndGet();
            close(session, CloseReason.CloseCodes.TRY_AGAIN_LATER, "Servidor lleno (máximo " + MAX_CLIENTS + ")");
            return;
        }
        session.getUserProperties().put("registered", Boolean.FALSE);
        session.getUserProperties().put("counted", Boolean.TRUE);
        LOG.info(() -> "Conexión WebSocket abierta: " + session.getId());
    }

    /**
     * El primer mensaje debe ser hello con el seudónimo; después se procesan
     * comandos JSON.
     */
    @OnMessage
    public void onText(String text, Session session) {
        try {
            JsonObject message = JsonParser.parseString(text).getAsJsonObject();
            String type = string(message, "type", "");
            if (!Boolean.TRUE.equals(session.getUserProperties().get("registered"))) {
                if (!"hello".equals(type)) {
                    error(session, "El primer mensaje debe ser hello con tu seudónimo.");
                    return;
                }
                register(session, string(message, "name", ""));
                return;
            }
            switch (type) {
                case "status_update":
                    updateStatus(session, string(message, "status", ""));
                    break;
                case "private_message":
                    privateMessage(session, message);
                    break;
                case "file_start":
                    startTransfer(session, message);
                    break;
                case "file_accept":
                    acceptTransfer(session, message);
                    break;
                case "file_chunk_ack":
                    acknowledgeChunk(session, message);
                    break;
                case "file_end":
                    endTransfer(session, message);
                    break;
                case "signal":
                    relaySignal(session, message);
                    break;
                case "room_create":
                    createRoom(session);
                    break;
                case "room_join":
                    joinRoom(session, string(message, "roomId", ""));
                    break;
                case "room_leave":
                    leaveRooms(session);
                    break;
                default:
                    error(session, "Tipo de mensaje desconocido.");
            }
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Mensaje WebSocket inválido de " + session.getId(), ex);
            error(session, "Mensaje inválido.");
        }
    }

    /**
     * Reenvía un fragmento binario al destinatario actual y confirma al emisor
     * para aplicar backpressure.
     */
    @OnMessage(maxMessageSize = 65536L)
    public void onBinary(ByteBuffer data, Session sender) {
        Transfer transfer = TRANSFERS.get(sender);
        if (transfer == null || !transfer.accepted) {
            error(sender, "El destinatario aún no aceptó el archivo.");
            return;
        }
        int length = data.remaining();
        if (length == 0 || length > MAX_CHUNK_BYTES || transfer.forwarded + length > transfer.size) {
            TRANSFERS.remove(sender);
            error(sender, "Fragmento inválido; transferencia cancelada.");
            return;
        }
        try {
            if (!transfer.target.isOpen()) {
                throw new IOException("Destinatario desconectado");
            }
            byte[] id = transfer.fileId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ByteBuffer copy = ByteBuffer.allocate(4 + id.length + length);
            copy.putInt(id.length).put(id).put(data.asReadOnlyBuffer()).flip();
            transfer.forwarded += length;
            synchronized (transfer.target) {
                transfer.target.getBasicRemote().sendBinary(copy);
            }
        } catch (Exception ex) {
            TRANSFERS.remove(sender);
            LOG.log(Level.WARNING, "Falló el reenvío de archivo", ex);
            error(sender, "Falló la transferencia: " + ex.getMessage());
        }
    }

    private void register(Session session, String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty() || name.length() > 32 || name.chars().anyMatch(Character::isISOControl)) {
            error(session, "El seudónimo debe tener de 1 a 32 caracteres.");
            return;
        }
        synchronized (CLIENTS) {
            boolean taken = CLIENTS.values().stream().anyMatch(c -> c.name.equalsIgnoreCase(name));
            if (taken) {
                error(session, "Ese seudónimo ya está en uso.");
                return;
            }
            CLIENTS.put(session, new Client(session, name));
        }
        session.getUserProperties().put("registered", Boolean.TRUE);
        LOG.info(() -> "Cliente activo: " + name + " [" + session.getId() + "]; total=" + CLIENTS.size());
        broadcastUsers();
    }

    private void updateStatus(Session session, String status) {
        if (!"online".equals(status) && !"away".equals(status) && !"dnd".equals(status)) {
            error(session, "Estado no válido.");
            return;
        }
        Client client = CLIENTS.get(session);
        if (client != null) {
            client.status = status;
            broadcastUsers();
        }
    }

    private void privateMessage(Session sender, JsonObject message) {
        Session target = findSession(string(message, "to", ""));
        String body = string(message, "text", "");
        if (target == null) {
            error(sender, "El destinatario ya no está conectado.");
            return;
        }
        if (body.isBlank() || body.length() > 4000) {
            error(sender, "El mensaje debe tener entre 1 y 4000 caracteres.");
            return;
        }
        JsonObject out = packet("private_message");
        out.addProperty("from", CLIENTS.get(sender).id);
        out.addProperty("name", CLIENTS.get(sender).name);
        out.addProperty("text", body);
        out.addProperty("time", Instant.now().toString());
        send(target, out);
    }

    /**
     * Registra metadatos pequeños; el contenido viaja en binario por bloques
     * sin acumularlo en RAM.
     */
    private void startTransfer(Session sender, JsonObject message) {
        String fileId = string(message, "fileId", "");
        String name = string(message, "name", "archivo");
        long size = message.has("size") ? message.get("size").getAsLong() : -1;
        Session target = findSession(string(message, "to", ""));
        if (TRANSFERS.containsKey(sender)) {
            error(sender, "Ya tienes una transferencia en curso.");
            return;
        }
        if (target == null || size < 0 || size > MAX_FILE_BYTES || fileId.isBlank() || name.length() > 255) {
            error(sender, "Archivo o destinatario inválido; límite: " + MAX_FILE_BYTES + " bytes.");
            return;
        }
        Transfer transfer = new Transfer(target, fileId, size);
        TRANSFERS.put(sender, transfer);
        JsonObject start = packet("file_offer");
        start.addProperty("fileId", fileId);
        start.addProperty("from", CLIENTS.get(sender).id);
        start.addProperty("name", name);
        start.addProperty("size", size);
        start.addProperty("mime", string(message, "mime", "application/octet-stream"));
        send(target, start);
    }

    private void acceptTransfer(Session receiver, JsonObject message) {
        String fileId = string(message, "fileId", "");
        for (Map.Entry<Session, Transfer> entry : TRANSFERS.entrySet()) {
            Transfer t = entry.getValue();
            if (t.target == receiver && t.fileId.equals(fileId)) {
                t.accepted = true;
                JsonObject ready = packet("file_ready");
                ready.addProperty("fileId", fileId);
                send(entry.getKey(), ready);
                return;
            }
        }
        error(receiver, "La oferta de archivo ya no está disponible.");
    }

    private void acknowledgeChunk(Session receiver, JsonObject message) {
        String fileId = string(message, "fileId", "");
        long bytes = message.has("bytes") ? message.get("bytes").getAsLong() : 0;
        for (Map.Entry<Session, Transfer> entry : TRANSFERS.entrySet()) {
            Transfer t = entry.getValue();
            if (t.target == receiver && t.fileId.equals(fileId) && bytes > 0 && t.received + bytes <= t.forwarded) {
                t.received += bytes;
                JsonObject ack = packet("file_ack");
                ack.addProperty("fileId", fileId);
                ack.addProperty("bytes", t.received);
                send(entry.getKey(), ack);
                return;
            }
        }
        error(receiver, "Confirmación de fragmento inválida.");
    }

    private void endTransfer(Session sender, JsonObject message) {
        Transfer transfer = TRANSFERS.get(sender);
        if (transfer == null || !transfer.fileId.equals(string(message, "fileId", ""))) {
            error(sender, "No existe esa transferencia.");
            return;
        }
        if (transfer.received != transfer.size || transfer.forwarded != transfer.size) {
            error(sender, "El archivo llegó incompleto (" + transfer.received + "/" + transfer.size + ").");
            return;
        }
        TRANSFERS.remove(sender);
        JsonObject done = packet("file_end");
        done.addProperty("fileId", transfer.fileId);
        send(transfer.target, done);
        JsonObject ack = packet("file_complete");
        ack.addProperty("fileId", transfer.fileId);
        send(sender, ack);
    }

    /**
     * Reenvía únicamente al par indicado o a los integrantes de la sala
     * indicada.
     */
    private void relaySignal(Session sender, JsonObject message) {
        JsonObject signal = message.has("data") && message.get("data").isJsonObject() ? message.getAsJsonObject("data") : new JsonObject();
        String roomId = string(message, "roomId", "");
        if (!roomId.isBlank()) {
            Room room = ROOMS.get(roomId);
            if (room == null || !room.members.containsKey(sender.getId())) {
                error(sender, "No perteneces a esa sala.");
                return;
            }
            String peerId = string(message, "to", "");
            if (!peerId.isBlank()) {
                Session member = room.members.get(peerId);
                if (member == null || member == sender) {
                    error(sender, "El par no pertenece a esa sala.");
                    return;
                }
                forwardSignal(sender, member, signal, roomId);
            } else {
                for (Session member : room.members.values()) {
                    if (member != sender) {
                        forwardSignal(sender, member, signal, roomId);
                    }
                }
            }
            return;
        }
        Session target = findSession(string(message, "to", ""));
        if (target == null) {
            error(sender, "Usuario para señalización no disponible.");
            return;
        }
        forwardSignal(sender, target, signal, "");
    }

    private void forwardSignal(Session from, Session to, JsonObject data, String roomId) {
        JsonObject out = packet("signal");
        out.addProperty("from", from.getId());
        out.addProperty("name", CLIENTS.get(from).name);
        if (!roomId.isBlank()) {
            out.addProperty("roomId", roomId);
        }
        out.add("data", data);
        send(to, out);
    }

    private void createRoom(Session session) {
        String id = java.util.UUID.randomUUID().toString().substring(0, 12);
        Room room = new Room(id);
        room.members.put(session.getId(), session);
        ROOMS.put(id, room);
        JsonObject out = packet("room_created");
        out.addProperty("roomId", id);
        send(session, out);
    }

    private void joinRoom(Session session, String roomId) {
        Room room = ROOMS.get(roomId);
        if (room == null || room.members.size() >= MAX_CLIENTS) {
            error(session, "Sala no disponible.");
            return;
        }
        if (room.members.putIfAbsent(session.getId(), session) != null) {
            error(session, "Ya perteneces a esa sala.");
            return;
        }
        JsonObject joined = packet("room_joined");
        joined.addProperty("roomId", roomId);
        joined.add("members", new com.google.gson.Gson().toJsonTree(room.members.keySet().stream().filter(id -> !id.equals(session.getId())).toArray()));
        send(session, joined);
        JsonObject notice = packet("room_peer_joined");
        notice.addProperty("roomId", roomId);
        notice.addProperty("peerId", session.getId());
        for (Session member : room.members.values()) {
            if (member != session) {
                send(member, notice);
            }
        }
    }

    private void leaveRooms(Session session) {
        for (Room room : ROOMS.values()) {
            if (room.members.remove(session.getId()) != null) {
                JsonObject notice = packet("room_peer_left");
                notice.addProperty("roomId", room.id);
                notice.addProperty("peerId", session.getId());
                for (Session member : room.members.values()) {
                    send(member, notice);
                }
                if (room.members.isEmpty()) {
                    ROOMS.remove(room.id, room);
                }
            }
        }
    }

    @OnClose
    public void onClose(Session session, CloseReason reason) {
        cleanup(session, reason.toString());
    }

    @OnError
    public void onError(Session session, Throwable error) {
        LOG.log(Level.WARNING, "Error de sesión " + session.getId(), error);
        close(session, CloseReason.CloseCodes.UNEXPECTED_CONDITION, "Error de conexión");
    }

    /**
     * Libera el cupo, cancela transferencias y retira la sesión de todas las
     * salas.
     */
    private void cleanup(Session session, String reason) {
        if (Boolean.TRUE.equals(session.getUserProperties().get("counted"))) {
            CONNECTIONS.decrementAndGet();
        }
        TRANSFERS.remove(session);
        leaveRooms(session);
        Client removed = CLIENTS.remove(session);
        if (removed != null) {
            LOG.info(() -> "Cliente desconectado: " + removed.name + " [" + session.getId() + "] " + reason);
            broadcastUsers();
        }
    }

    /**
     * Publica el roster actual con identificador, seudónimo y presencia de cada
     * sesión.
     */
    private void broadcastUsers() {
        JsonObject out = packet("user_list");
        com.google.gson.JsonArray users = activeUsers();
        out.add("users", users);
        String roster = CLIENTS.values().stream().map(c -> c.name + "[" + c.id + ", " + c.status + "]").collect(java.util.stream.Collectors.joining(", "));
        LOG.info(() -> "Usuarios activos (" + CLIENTS.size() + "): " + roster);
        CLIENTS.keySet().forEach(s -> send(s, out));
        AdminEndpoint.publish(users);
    }

    /** Devuelve una copia JSON del roster para el panel protegido y los clientes. */
    static com.google.gson.JsonArray activeUsers() {
        com.google.gson.JsonArray users = new com.google.gson.JsonArray();
        CLIENTS.values().forEach(c -> {
            JsonObject u = new JsonObject();
            u.addProperty("id", c.id);
            u.addProperty("name", c.name);
            u.addProperty("status", c.status);
            users.add(u);
        });
        return users;
    }

    private Session findSession(String id) {
        return CLIENTS.entrySet().stream().filter(e -> e.getValue().id.equals(id)).map(Map.Entry::getKey).findFirst().orElse(null);
    }

    private static String string(JsonObject obj, String key, String fallback) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : fallback;
    }

    private static JsonObject packet(String type) {
        JsonObject out = new JsonObject();
        out.addProperty("type", type);
        return out;
    }

    private static void send(Session session, JsonObject message) {
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            synchronized (session) {
                session.getBasicRemote().sendText(message.toString());
            }
        } catch (IOException ex) {
            LOG.log(Level.FINE, "No se pudo enviar a " + session.getId(), ex);
        }
    }

    private static void error(Session session, String text) {
        JsonObject out = packet("error");
        out.addProperty("message", text);
        send(session, out);
    }

    private static void close(Session session, CloseReason.CloseCode code, String reason) {
        try {
            session.close(new CloseReason(code, reason));
        } catch (IOException ex) {
            LOG.log(Level.FINE, "No se pudo cerrar sesión", ex);
        }
    }
}
