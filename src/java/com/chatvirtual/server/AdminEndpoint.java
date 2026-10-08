package com.chatvirtual.server;

import com.google.gson.JsonArray;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Canal WebSocket de solo lectura para un panel administrativo autenticado. */
@ServerEndpoint("/admin")
public class AdminEndpoint {
    private static final Logger LOG = Logger.getLogger(AdminEndpoint.class.getName());
    private static final Set<Session> ADMINS = ConcurrentHashMap.newKeySet();
    private static final String ADMIN_TOKEN = configuredToken();

    @OnOpen
    public void onOpen(Session session) {
        if (ADMIN_TOKEN == null) {
            send(session, packet("admin_disabled", "El administrador debe configurar CHAT_ADMIN_TOKEN en Tomcat."));
            close(session, CloseReason.CloseCodes.VIOLATED_POLICY, "Panel administrativo no configurado");
        }
    }

    /** Valida el token por el canal cifrado y envía el roster actual al administrador. */
    @OnMessage
    public void onMessage(String text, Session session) {
        try {
            JsonObject message = JsonParser.parseString(text).getAsJsonObject();
            if (!Boolean.TRUE.equals(session.getUserProperties().get("admin"))) {
                authenticate(session, message);
                return;
            }
            if ("refresh".equals(string(message, "type", ""))) {
                sendRoster(session);
            }
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Mensaje administrativo inválido", ex);
            send(session, packet("error", "Solicitud administrativa inválida."));
        }
    }

    private void authenticate(Session session, JsonObject message) {
        String token = string(message, "token", "");
        boolean valid = ADMIN_TOKEN != null && "authenticate".equals(string(message, "type", ""))
                && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), ADMIN_TOKEN.getBytes(StandardCharsets.UTF_8));
        if (!valid) {
            send(session, packet("error", "Token de administrador incorrecto."));
            close(session, CloseReason.CloseCodes.VIOLATED_POLICY, "Autenticación fallida");
            return;
        }
        session.getUserProperties().put("admin", Boolean.TRUE);
        ADMINS.add(session);
        sendRoster(session);
    }

    /** Notifica al panel los cambios de presencia sin publicar la lista a usuarios normales. */
    static void publish(JsonArray users) {
        JsonObject update = new JsonObject();
        update.addProperty("type", "admin_users");
        update.add("users", users.deepCopy());
        ADMINS.forEach(session -> send(session, update));
    }

    private void sendRoster(Session session) {
        JsonObject update = new JsonObject();
        update.addProperty("type", "admin_users");
        update.add("users", ChatServer.activeUsers());
        send(session, update);
    }

    @OnClose
    public void onClose(Session session) {
        ADMINS.remove(session);
    }

    @OnError
    public void onError(Session session, Throwable error) {
        ADMINS.remove(session);
        LOG.log(Level.WARNING, "Error en el panel administrativo", error);
    }

    private static String configuredToken() {
        String token = System.getProperty("chat.adminToken");
        if (token == null || token.isBlank()) {
            token = System.getenv("CHAT_ADMIN_TOKEN");
        }
        return token == null || token.isBlank() ? null : token;
    }

    private static String string(JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
    }

    private static JsonObject packet(String type, String message) {
        JsonObject packet = new JsonObject();
        packet.addProperty("type", type);
        packet.addProperty("message", message);
        return packet;
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
            LOG.log(Level.FINE, "No se pudo actualizar el panel administrativo", ex);
        }
    }

    private static void close(Session session, CloseReason.CloseCode code, String reason) {
        try {
            session.close(new CloseReason(code, reason));
        } catch (IOException ex) {
            LOG.log(Level.FINE, "No se pudo cerrar la sesión administrativa", ex);
        }
    }
}
