package com.nhncorp.mods.socket.io.impl;

import com.nhncorp.mods.socket.io.SocketIOSocket;
import com.nhncorp.mods.socket.io.Util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vertx.java.core.Handler;
import org.vertx.java.core.eventbus.EventBus;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.shareddata.Shareable;

import java.util.Map;

/**
 * Namespace
 *
 * @see <a href="https://github.com/LearnBoost/socket.io/blob/master/lib/namespace.js">namespace.js</a>
 * @author Keesun Baik
 */
public class Namespace implements Shareable {

	private static final Logger log = LoggerFactory.getLogger(Namespace.class);

	private final String name;
	private final Manager manager;
	private Map<String, SocketIOSocket> sockets;
	private EventBus eventBus;
	private JsonObject flags;
	private Parser parser;
	private AuthorizationHandler authHandler;
	private Handler<SocketIOSocket> socketHandler;

	public Namespace(final Manager manager, final String name) {
		this.manager = manager;
		this.name = name != null ? name : "";

		this.sockets = manager.getMap(name);
		this.eventBus = manager.getVertx().eventBus();
		this.parser = new Parser();
		setFlags();
	}

	public Namespace onConnection(Handler<SocketIOSocket> handler) {
		this.socketHandler = handler;
		return this;
	}

	/**
	 * Access store.
	 */
	public Store store() {
		return this.manager.getStore();
	}

	/**
	 * JSON message flag.
	 */
	public Namespace json() {
		this.flags.putBoolean("json", true);
		return this;
	}

	/**
	 * Volatile message flag.
	 */
	public Namespace volatilize() {
		this.flags.putBoolean("volatile", true);
		return this;
	}

	/**
	 * Overrides the room to relay messages to (flag).
	 *
	 * @see "SocketNamespace.prototype.in"
	 * @param room
	 * @return
	 */
	public Namespace in(String room) {
		this.flags.putString("endpoint", this.getName() + (room != null ? "/" + room : ""));
		return this;
	}

	/**
	 * Overrides the room to relay messages to (flag).
	 *
	 * @see "SocketNamespace.prototype.to"
	 * @param room
	 * @return
	 */
	public Namespace to(String room) {
		return in(room);
	}

	/**
	 * Adds a session id we should prevent relaying messages to (flag).
	 *
	 * @see "SocketNamespace.prototype.except"
	 * @param id
	 * @return
	 */
	public Namespace except(String id) {
		this.flags.getArray("exceptions").add(id);
		return this;
	}

	/**
	 * Sets the default flags.
	 *
	 * @see "SocketNamespace.prototype.setFlags"
	 */
	private void setFlags() {
		this.flags = new JsonObject();
		flags.putString("endpoint", this.name);
		flags.putArray("exceptions", new JsonArray());
	}

	/**
	 * Sends out a packet.
	 *
	 * @see "SocketNamespace.prototype.packet"
	 * @param packet
	 * @return
	 */
	public Namespace packet(JsonObject packet) {
		packet.putString("endpoint", this.getName());
		Store store = this.manager.getStore();
		boolean isVolatile = this.flags.getBoolean("volatile", false);
		JsonArray exceptions = this.flags.getArray("exceptions");
		String encodedPacket = parser.encodePacket(packet);

		this.manager.onDispatch(this.flags.getString("endpoint"), encodedPacket, isVolatile, exceptions);
		//		this.store.publish('dispatch', this.flags.endpoint, packet, volatile, exceptions);
		this.setFlags();

		return this;
	}

	/**
	 * Sends to everyone.
	 * @see "SocketNamespace.prototype.send"
	 */
	public void send(JsonObject data) {
		JsonObject packet = new JsonObject();
		packet.putString("type", this.flags.getBoolean("json", false) ? "json" : "message");
		packet.putObject("data", data);
		packet(packet);
	}

	/**
	 * Emits to everyone (override).
	 *
	 * @see "SocketNamespace.prototype.emit"
	 */
	public void emit(String event, JsonObject jsonObject) {
		JsonObject packet = new JsonObject();
		packet.putString("type", "event");
		packet.putString("name", event);

		JsonArray args = new JsonArray();
		args.addObject(jsonObject);
		packet.putArray("args", args);
		packet(packet);
	}

	/**
	 * Emit String data
	 *
	 * @param event
	 * @param data
	 */
	public void emit(String event, String data) {
		JsonObject packet = new JsonObject();
		packet.putString("type", "event");
		packet.putString("name", event);
		packet.putArray("args", new JsonArray().addString(data));
		packet(packet);
	}

	/**
	 * Retrieves or creates a write-only socket for a client, unless specified.
	 *
	 * @see "SocketNamespace.prototype.socket"
	 * @param sid
	 * @param readable whether the socket will be readable when initialized
	 * @return
	 */
	public SocketIOSocket socket(String sid, boolean readable) {
		if (!this.sockets.containsKey(sid)) {
			this.sockets.put(sid, new DefaultSocketIOSocket(this.manager, sid, this, readable, this.socketHandler));
		}
		return this.sockets.get(sid);
	}

	/**
	 * Sets authorization for this namespace.
	 *
	 * @see "SocketNamespace.prototype.authorization"
	 *
	 */
	public Namespace authorization(AuthorizationHandler handler) {
		this.authHandler = handler;
		return this;
	}

	/**
	 * Called when a socket disconnects entirely.
	 *
	 * @see "SocketNamespace.prototype.handleDisconnect"
	 * @param sessionId
	 * @param reason
	 * @param raiseOnDisconnect
	 */
	public void handleDisconnect(String sessionId, String reason, boolean raiseOnDisconnect) {
		SocketIOSocket socket = sockets.get(sessionId);
		if (socket != null && socket.isReadable()) {
			if (raiseOnDisconnect) {
				socket.onDisconnect(reason);
			}
			if (sockets.containsKey(sessionId)) {
				sockets.remove(sessionId);
			}
		}
	}

	/**
	 * Performs authentication.
	 *
	 * @see "SocketNamespace.prototype.authorize"
	 * @param handshakeData
	 * @param authCallback
	 */
	private void authorize(HandshakeData handshakeData, final AuthorizationCallback authCallback) {
		if (this.authHandler != null) {
			authHandler.handle(handshakeData, new AuthorizationCallback() {
				public void handle(Exception e, boolean isAuthorized) {
					log.debug("client " + (isAuthorized ? "" : "un") + "authorized for " + name);
					authCallback.handle(e, isAuthorized);
				}
			});
		} else {
			log.debug("client authorized for " + this.getName());
			authCallback.handle(null, true);
		}
	}

	/**
	 * Handles a packet.
	 *
	 * @see "SocketNamespace.prototype.handlePacket"
	 * @param sessionId
	 * @param packet
	 */
	public void handlePacket(final String sessionId, final JsonObject packet) {
		final SocketIOSocket socket = socket(sessionId, true);
		boolean isDataAck = false;
		String ack = packet.getString("ack");
		if (ack != null && ack.equals("data")) {
			isDataAck = true;
		}

		String type = packet.getString("type");

        Handler<Message<JsonObject>> replyHandler = new Handler<Message<JsonObject>>() {
            @Override
            public void handle(Message<JsonObject> reply) {
                JsonObject obj = new JsonObject();
                obj.putString("type", "ack");
                obj.putString("ackId", packet.getString("id"));
                JsonArray args = new JsonArray();
                args.addObject(reply.body());
                obj.putArray("args", args);
                socket.packet(obj);
            }
        };

		switch (type) {
			case "connect":
				String endpoint = packet.getString("endpoint", "");
				if (endpoint.equals("")) {
					connect(socket);
				} else {
					final HandshakeData handshakeData = manager.getHandshaken().get(sessionId);

					this.authorize(handshakeData, new AuthorizationCallback() {
						@Override
						public void handle(Exception e, boolean isAuthorized) {
							if (e != null) {
								error(socket, e);
								return;
							}

							if (isAuthorized) {
								manager.onHandshake(sessionId, handshakeData);
								//								self.store.publish('handshake', sessid, newData || handshakeData);
								connect(socket);
							} else {
								error(socket, e);
							}
						}
					});
				}
				break;

			case "ack":
                Handler ackHandler = socket.getAcks().get(packet.getString("ackId"));
				if (ackHandler != null) {

                    String name = packet.getString("name", "message");
                    JsonObject ackPack = Util.flatten(packet.getArray("args"));
                    if(name.equals("disconnect")) {
                        ackPack.putString("reason", packet.getString("reason"));
                    }

                    Object message = packet.getField("message");
                    if(message != null) {
                        if(message instanceof String) {
                            ackPack.putString("message", packet.getString("message"));
                        } else if(message instanceof JsonObject) {
                            ackPack.putObject("message", packet.getObject("message"));
                        }
                    }


					ackHandler.handle(ackPack);
				} else {
                    log.info("unknown ack packet " + packet.getString("ackId") + " not matching any pending: " + socket.getAcks().keySet());
                }
				break;

			case "event":
				// check if the emitted event is not blacklisted
				if (manager.getSettings().getBlacklist().indexOf(packet.getString("name")) != -1) {
					log.debug("ignoring blacklisted event \'" + packet.getString("name") + "\'");
				} else {
					JsonObject params = new JsonObject();
					JsonArray args = packet.getArray("args");
					if(args != null)
						params.putArray("args", args);
					params.putString("name", packet.getString("name"));
					if (isDataAck) {
						params.putString("ack", packet.getString("ack"));
                        socket.emitOnEB(params, replyHandler);
					} else {
                        socket.emitOnEB(params);
                    }

				}
				break;
			case "disconnect":
				this.manager.onLeave(sessionId, this.name);
				//				this.store.publish('leave', sessid, this.name);
				socket.emitDisconnect(packet.getString("reason", "packet"));
				break;
			case "json":
			case "message":
				JsonObject params = new JsonObject();
				params.putString("name", "message");

				Object data = packet.getField("data");
				if(data instanceof String) {
					params.putString("message", packet.getString("data"));
				} else if(data instanceof JsonObject) {
					params.putObject("message", packet.getObject("data"));
				}

				if (isDataAck) {
					params.putString("ack", ack);
                    socket.emitOnEB(params,replyHandler);
                } else {
                    socket.emitOnEB(params);
                }
		}
	}

	/**
	 * @see "SocketNamespace.prototype.handlePacket ack"
	 * @param socket
	 * @param jsonObject
	 */
	private void ack(SocketIOSocket socket, JsonObject jsonObject) {
		if (log.isDebugEnabled())
			log.debug("sending data ack packet");
		JsonObject packet = new JsonObject();
		packet.putString("type", "ack");
		packet.putArray("args", jsonObject.getArray("args"));
		packet.putString("ackId", jsonObject.getString("ackId"));
		socket.packet(packet);
	}

	/**
	 * @see "SocketNamespace.prototype.handlePacket error"
	 * @param socket
	 * @param e
	 */
	private void error(SocketIOSocket socket, Exception e) {
		log.warn("handshake error " + e.getMessage() + " for " + this.name);
		JsonObject packet = new JsonObject();
		packet.putString("type", "error");
		packet.putString("reason", e.getMessage());
		socket.packet(packet);
	}

	/**
	 * @see "SocketNamespace.prototype.handlePacket connect"
	 * @param socket
	 */
	private void connect(SocketIOSocket socket) {
		this.manager.onJoin(socket.getId(), this.name);
		//		self.store.publish('join', sessid, self.name);

		// packet echo
		JsonObject packet = new JsonObject();
		packet.putString("type", "connect");
		socket.packet(packet);

		// emit connection event
		socket.onConnection();
	}

	public String getName() {
		return name;
	}

	public void emit(String event) {
		JsonObject packet = new JsonObject();
		packet.putString("type", "event");
		packet.putString("name", event);
		packet(packet);
	}

	public Namespace setAuthHandler(AuthorizationHandler authHandler) {
		this.authHandler = authHandler;
		return this;
	}

	public Manager manager() {
		return this.manager;
	}

	public String[] clients(String roomName) {
		Room room = this.manager().rooms().get(this.getName() + "/" + roomName);
		if(room == null)
			return null;
		return room.values();
	}
}