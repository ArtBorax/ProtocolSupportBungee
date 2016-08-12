package protocolsupport.protocol.listeners.initial;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.util.ReferenceCountUtil;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.concurrent.TimeUnit;

import protocolsupport.api.ProtocolVersion;
import protocolsupport.protocol.listeners.IPipeLineBuilder;
import protocolsupport.protocol.storage.ProtocolStorage;
import protocolsupport.utils.Utils;

//TODO: Copy from protocolsupport
public class InitialPacketDecoder extends ChannelInboundHandlerAdapter {

	public static final String NAME = "initial_decoder";

	private static final EnumMap<ProtocolVersion, IPipeLineBuilder> pipelineBuilders = new EnumMap<ProtocolVersion, IPipeLineBuilder>(ProtocolVersion.class);
	static {
		protocolsupport.protocol.transformer.v_1_4_1_5_1_6_core.PipeLineBuilder legacyBuilder = new protocolsupport.protocol.transformer.v_1_4_1_5_1_6_core.PipeLineBuilder();
		pipelineBuilders.put(ProtocolVersion.MINECRAFT_1_6_4, legacyBuilder);
		pipelineBuilders.put(ProtocolVersion.MINECRAFT_1_6_2, legacyBuilder);
		pipelineBuilders.put(ProtocolVersion.MINECRAFT_1_5_2, legacyBuilder);
		pipelineBuilders.put(ProtocolVersion.MINECRAFT_1_4_7, legacyBuilder);
	}

	protected ByteBuf receivedData = Unpooled.buffer();

	protected volatile boolean protocolSet = false;

	@SuppressWarnings("deprecation")
	@Override
	public void channelRead(final ChannelHandlerContext ctx, final Object inputObj) throws Exception {
		try {
			final ByteBuf input = (ByteBuf) inputObj;
			if (!input.isReadable()) {
				return;
			}
			final Channel channel = ctx.channel();
			receivedData.writeBytes(input.readBytes(input.readableBytes()));
			ProtocolVersion handshakeversion = ProtocolVersion.UNKNOWN;
			receivedData.readerIndex(0);
			int firstbyte = receivedData.readUnsignedByte();
			switch (firstbyte) {
				case 0xFE: { //old ping (should we check if FE is actually a part of a varint length?)
					try {
						if (receivedData.readableBytes() == 0) { //really old protocol probably
							ctx.executor().schedule(new OldPingResponseTask(this, channel), 1000, TimeUnit.MILLISECONDS);
						} else if (receivedData.readUnsignedByte() == 1) {
							if (receivedData.readableBytes() == 0) {
								//1.5.2 probably
								ctx.executor().schedule(new Minecraft152PingResponseTask(this, channel), 500, TimeUnit.MILLISECONDS);
							} else if (
								(receivedData.readUnsignedByte() == 0xFA) &&
								"MC|PingHost".equals(new String(Utils.readBytes(receivedData, receivedData.readUnsignedShort() * 2), StandardCharsets.UTF_16BE))
							) { //1.6.*
								receivedData.readUnsignedShort();
								handshakeversion = ProtocolVersion.fromId(receivedData.readUnsignedByte());
							}
						}
					} catch (IndexOutOfBoundsException ex) {
					}
					break;
				}
				case 0x02: { //1.6 or 1.5.2 handshake
					try {
						handshakeversion = ProtocolVersion.fromId(receivedData.readUnsignedByte());
					} catch (IndexOutOfBoundsException ex) {
					}
					break;
				}
				default: { //1.7 or 1.8 handshake
					receivedData.readerIndex(0);
					ByteBuf data = getVarIntPrefixedData(receivedData);
					if (data != null) {
						handshakeversion = readNettyHandshake(data);
					}
					break;
				}
			}
			//if we detected the protocol than we save it and process data
			if (handshakeversion != ProtocolVersion.UNKNOWN) {
				setProtocol(channel, receivedData, handshakeversion);
			}
		} catch (Throwable t) {
			ctx.channel().close();
		} finally {
			ReferenceCountUtil.release(inputObj);
		}
	}

	protected void setProtocol(final Channel channel, final ByteBuf input, ProtocolVersion version) throws Exception {
		if (protocolSet) {
			return;
		}
		protocolSet = true;
		ProtocolStorage.setProtocolVersion(channel.remoteAddress(), version);
		channel.pipeline().remove(NAME);
		IPipeLineBuilder builder = pipelineBuilders.get(version);
		if (builder != null) {
			builder.buildPipeLine(channel, version);
		}
		input.readerIndex(0);
		channel.pipeline().firstContext().fireChannelRead(input);
	}


	private ByteBuf getVarIntPrefixedData(final ByteBuf byteBuf) {
		final byte[] array = new byte[3];
		for (int i = 0; i < array.length; ++i) {
			if (!byteBuf.isReadable()) {
				return null;
			}
			array[i] = byteBuf.readByte();
			if (array[i] >= 0) {
				final int length = readVarInt(Unpooled.wrappedBuffer(array));
				if (byteBuf.readableBytes() < length) {
					return null;
				}
				return byteBuf.readBytes(length);
			}
		}
		throw new CorruptedFrameException("Packet length is wider than 21 bit");
	}

	@SuppressWarnings("deprecation")
	private ProtocolVersion readNettyHandshake(ByteBuf data) {
		if (readVarInt(data) == 0x00) {
			return ProtocolVersion.fromId(readVarInt(data));
		}
		return ProtocolVersion.UNKNOWN;
	}

	private int readVarInt(ByteBuf data) {
		int value = 0;
		int length = 0;
		byte b0;
		do {
			b0 = data.readByte();
			value |= (b0 & 0x7F) << (length++ * 7);
			if (length > 5) {
				throw new RuntimeException("VarInt too big");
			}
		} while ((b0 & 0x80) == 0x80);
		return value;
	}

}
