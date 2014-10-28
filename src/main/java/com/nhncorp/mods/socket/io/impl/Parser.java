package com.nhncorp.mods.socket.io.impl;

import org.vertx.java.core.buffer.Buffer;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.json.impl.Json;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @see <a href="https://github.com/LearnBoost/socket.io/blob/master/lib/parser.js">parser.js</a>
 * @author Keesun Baik
 */
public class Parser {

	Map<String, Integer> packets;
	String[] packetslist;
	Map<String, Integer> reasons;
	String[] reasonsList;
	Map<String, Integer> advices;
	String[] adviceList;

	public Parser() {
		packetslist = new String[]{"disconnect", "connect", "heartbeat", "message", "json", "event", "ack", "error", "noop"};
		packets = new HashMap<>();
		fillMapWithList(packets, packetslist);

		reasonsList = new String[]{"transport not supported", "client not handshaken", "unauthorized"};
		reasons = new HashMap<>();
		fillMapWithList(reasons, reasonsList);

		adviceList = new String[]{"reconnect"};
		advices = new HashMap<>();
		fillMapWithList(advices, adviceList);
	}

	private void fillMapWithList(Map<String, Integer> map, String[] list) {
		int index = 0;
		for(String key : list) {
			map.put(key, index++);
		}
	}

	/**
	 * Encodes a packet.
	 *
	 * @see "exports.encodePacket"
	 * @param packet
	 * @return
	 */
	public String encodePacket(JsonObject packet) {
		String typeKey = packet.getString("type");
		int type = packets.get(typeKey);
		String id = packet.getString("id", "");
		String endpoint = packet.getString("endpoint", "");
		String ack = packet.getString("ack");
		String data = null;

		switch (typeKey) {
			case "message":
				String dataString = packet.getString("data");
				if(dataString != null && !dataString.isEmpty()) {
					data = dataString;
				}
				break;

			case "event":
				JsonObject ev = new JsonObject();
				ev.putString("name", packet.getString("name"));

				JsonArray args = packet.getArray("args");
				if(args != null && args.size() > 0) {
					ev.putArray("args", args);
				}

				data = ev.toString();
				break;

			case "json":
				data = Json.encode(packet.getString("data"));
				break;

			case "ack":
				data = packet.getString("ackId");
				args = packet.getArray("args");
				if(args != null && args.size() > 0) {
					data += "+" + args.encode();
				}
				break;

			case "connect":
				String qs = packet.getString("qs");
				if(qs != null) {
					data = qs;
				}
				break;

			case "error":
				Integer reasonValue = null;
				Integer adviceValue = null;

				String reasonString = packet.getString("reason");
				if(reasonString != null && !reasonString.isEmpty()) {
					reasonValue = this.reasons.get(reasonString);
				}

				String adviceString = packet.getString("advice");
				if(adviceString != null && !adviceString.isEmpty()) {
					adviceValue = this.advices.get(adviceString);
				}

				if(reasonValue != null || adviceValue != null) {
					data = "" + reasonValue + ( (adviceValue != null) ? ("+" + adviceValue) : "");
				}
				break;
		}

		// construct packet with required fragments
		String encoded = type + ":" + id + ((ack != null && ack.equals("data")) ? "+" : "") + ":" + endpoint;

		// data fragment is optional
		if (data != null) {
			encoded += ":" + data;
		}

		return encoded;
	}

	/**
	 * Encodes multiple messages (payload).
	 *
	 * @see "exports.encodePayload"
	 * @param buffers
	 * @return
	 */
	public String encodePayload(List<Buffer> buffers) {
		String decoded = "";

		if(buffers.size() == 1) {
			return buffers.get(0).toString();
		}

		for(Buffer buffer : buffers) {
			decoded += "\ufffd" + buffer.length() + "\ufffd" + buffer.toString();
		}

		return decoded;
	}

	/**
	 * @see "exports.decodePacket"
	 * @param packetData
	 * @return
	 */
	public JsonObject decodePacket(String packetData) {
		String regexp = "([^:]+):([0-9]+)?(\\+)?:([^:]+)?:?([\\s\\S]*)?";
		String[] pieces = RegexUtils.match(packetData, regexp);

		if(pieces[0] == null) {
			return new JsonObject();
		}

		String id = (pieces[2] != null) ? pieces[2] : "";
		String data = (pieces[5] != null) ? pieces[5] : "";

		JsonObject packet = new JsonObject();
		packet.putString("type", packetslist[Integer.parseInt(pieces[1])]);
		packet.putString("endpoint", (pieces[4] != null) ? pieces[4] : "");

		// whether we need to acknowledge the packet
		if(!id.isEmpty()) {
			packet.putString("id", id);
			if(pieces[3] != null) {
				packet.putString("ack", "data");
			} else {
				packet.putString("ack", "true");
			}
		}

		// handle different packet types
		switch (packet.getString("type")) {
			case "message":
				packet.putString("data", data);
				break;
			case "event":
				JsonObject parsedData = new JsonObject(data);
				packet.putString("name", parsedData.getString("name"));
				JsonArray args = parsedData.getArray("args");
				if(args != null) {
					packet.putArray("args", args);
				}
				break;
			case "json":
				packet.putObject("data", new JsonObject(data));
				break;
			case "connect":
				packet.putString("qs", data);
				break;
			case "ack":
				String ackRegexp = "^([0-9]+)(\\+)?(.*)";
				String[] piecedData = RegexUtils.match(data, ackRegexp);
				if(piecedData[0] != null) {
					packet.putString("ackId", piecedData[1]);
					packet.putArray("args", new JsonArray());
					if(piecedData[3] != null) {
						JsonArray ackArgs;
                        String sArgs = piecedData[3];
                        try {
                            JsonObject jsArgs = new JsonObject(sArgs);
                            ackArgs = new JsonArray();
                            ackArgs.addObject(jsArgs);
                        } catch (Exception e) {
                            // System.err.println("data as JsonObject failed. Trying JsonArray");
                            ackArgs = new JsonArray(sArgs);
                        }

						packet.putArray("args", ackArgs);
					}
				}
				break;
			case "error":
				String[] parts = data.split("\\+");
				String reason = reasonsList[Integer.parseInt(parts[0])];
				if(reason != null) packet.putString("reason", reason);
				String advice = adviceList[Integer.parseInt(parts[1])];
				if(advice != null) packet.putString("advice", advice);
		}

		return packet;
	}

	/**
	 * Decodes data payload. Detects multiple messages
	 *
	 * @see "exports.decodePayload"
	 * @param data
	 * @return
	 */
	public List<JsonObject> decodePayload(Buffer data) {
		List<JsonObject> ret = new ArrayList<>();

		if(data == null) {
			return ret;
		}

		if(data.getString(0, 1).equals("\ufffd")) {
			for(int i = 1, length = 0 ; i < data.length() ; i++) {
				if(data.getString(i, i+1).equals("\ufffd")) {
					int start = i + 1;
					int end = start + length;
					ret.add(decodePacket(data.getString(i+1, end)));
					i += end + 1;
					length = 0;
				} else {
					length += data.getInt(i);
				}
			}
		} else {
			ret.add(decodePacket(data.toString()));
		}
		return ret;
	}


    public static void main(String[] args) {
        String eventPacket = "5:2+::{\"name\":\"request\",\"args\":[{\"resource\":{\"contact\":\"ed90e9a4-8ed3-42fe-8114-abd8b196b7f4\",\"delayTolerance\":\"10\",\"aggregateURI\":\"\"},\"method\":\"create\",\"typename\":\"subscription\",\"path\":\"/m2m/applications/myApp/subscriptions\"}]}";
        String ackPacket = "6:::2+[{\"result\":{\"statusCode\":201,\"resourceURI\":\"/m2m/applications/myApp/subscriptions/subscription-Ygo7CQIb6HS5v9EC\",\"primitiveType\":\"create\",\"resource\":{\"subscription\":{\"id\":\"subscription-Ygo7CQIb6HS5v9EC\",\"lastModifiedTime\":\"2014-10-09T13:45:31.953000+00:00\",\"delayTolerance\":\"10\",\"aggregateURI\":\"\",\"noRepresentation\":null,\"expirationTime\":\"2014-10-09T14:45:31.953000+00:00\",\"timeoutReason\":null,\"filterCriteria\":null,\"path\":\"/m2m/applications/myApp/subscriptions/subscription-Ygo7CQIb6HS5v9EC\",\"subscriptionType\":null,\"subscriberId\":null,\"creationTime\":\"2014-10-09T13:45:31.953000+00:00\",\"contact\":\"sio:///ed90e9a4-8ed3-42fe-8114-abd8b196b7f4\",\"minimalTimeBetweenNotifications\":null}},\"fields\":[\"expirationTime\"]},\"status\":\"ok\"}]";
        String ackPacket2 = "6:::1+[\"200 OK\"]";

        Parser p = new Parser();
        System.out.println("Testing parser: " + eventPacket);
        System.out.println("result:\r\n" + p.decodePacket(eventPacket).encodePrettily());
        System.out.println();

        System.out.println("Testing parser: " + ackPacket);
        System.out.println("result:\r\n" + p.decodePacket(ackPacket).encodePrettily());
        System.out.println();

        System.out.println("Testing parser: " + ackPacket2);
        System.out.println("result:\r\n" + p.decodePacket(ackPacket2).encodePrettily());
        System.out.println();


    }

}