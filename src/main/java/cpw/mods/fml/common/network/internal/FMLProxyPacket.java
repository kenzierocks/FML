package cpw.mods.fml.common.network.internal;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;

import java.io.IOException;
import java.util.List;

import net.minecraft.network.INetHandler;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.client.C17PacketCustomPayload;
import net.minecraft.network.play.server.S3FPacketCustomPayload;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.helpers.Integers;

import com.google.common.collect.ConcurrentHashMultiset;
import com.google.common.collect.Lists;
import com.google.common.collect.Multiset;
import com.google.common.collect.Multiset.Entry;
import com.google.common.collect.Multisets;

import cpw.mods.fml.common.FMLLog;
import cpw.mods.fml.common.network.FMLNetworkException;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.handshake.NetworkDispatcher;
import cpw.mods.fml.relauncher.Side;

public class FMLProxyPacket implements Packet {
    final String channel;
    private Side target;
    private final PacketBuffer payload;
    private INetHandler netHandler;
    private NetworkDispatcher dispatcher;
    private static Multiset<String> badPackets = ConcurrentHashMultiset.create();
    private static int packetCountWarning = Integers.parseInt(System.getProperty("fml.badPacketCounter", "100"), 100);

    public FMLProxyPacket(S3FPacketCustomPayload original)
    {
        this(original.func_180735_b(), original.func_149169_c());
        this.target = Side.CLIENT;
    }

    public FMLProxyPacket(C17PacketCustomPayload original)
    {
        this(original.func_180760_b(), original.func_149559_c());
        this.target = Side.SERVER;
    }

    public FMLProxyPacket(PacketBuffer payload, String channel)
    {
        this.channel = channel;
        this.payload = payload;
    }
    @Override
    public void readPacketData(PacketBuffer packetbuffer) throws IOException
    {
        // NOOP - we are not built this way
    }

    @Override
    public void writePacketData(PacketBuffer packetbuffer) throws IOException
    {
        // NOOP - we are not built this way
    }

    @Override
    public void processPacket(INetHandler inethandler)
    {
        this.netHandler = inethandler;
        EmbeddedChannel internalChannel = NetworkRegistry.INSTANCE.getChannel(this.channel, this.target);
        if (internalChannel != null)
        {
            internalChannel.attr(NetworkRegistry.NET_HANDLER).set(this.netHandler);
            try
            {
                if (internalChannel.writeInbound(this))
                {
                    badPackets.add(this.channel);
                    if (badPackets.size() % packetCountWarning == 0)
                    {
                        FMLLog.severe("Detected ongoing potential memory leak. %d packets have leaked. Top offenders", badPackets.size());
                        int i = 0;
                        for (Entry<String> s  : Multisets.copyHighestCountFirst(badPackets).entrySet())
                        {
                            if (i++ > 10) break;
                            FMLLog.severe("\t %s : %d", s.getElement(), s.getCount());
                        }
                    }
                }
                internalChannel.inboundMessages().clear();
            }
            catch (FMLNetworkException ne)
            {
                FMLLog.log(Level.ERROR, ne, "There was a network exception handling a packet on channel %s", channel);
                dispatcher.rejectHandshake(ne.getMessage());
            }
            catch (Throwable t)
            {
                FMLLog.log(Level.ERROR, t, "There was a critical exception handling a packet on channel %s", channel);
                dispatcher.rejectHandshake("A fatal error has occured, this connection is terminated");
            }
        }
    }

    public String channel()
    {
        return channel;
    }
    public ByteBuf payload()
    {
        return payload;
    }
    public INetHandler handler()
    {
        return netHandler;
    }
    public Packet toC17Packet()
    {
        return new C17PacketCustomPayload(channel, payload);
    }

    static final int PART_SIZE = 0x1000000 - 0x50; // Make it a constant so that it gets inlined below.
    public static final int MAX_LENGTH = PART_SIZE * 255;
    public List<Packet> toS3FPackets() throws IOException
    {
        List<Packet> ret = Lists.newArrayList();
        byte[] data = payload.array();

        if (data.length < PART_SIZE)
        {
            ret.add(new S3FPacketCustomPayload(channel, payload));
        }
        else
        {
            int parts = (int)Math.ceil(data.length / (double)(PART_SIZE - 1)); //We add a byte header so -1
            if (parts > 255)
            {
                throw new IllegalArgumentException("Payload may not be larger than " + MAX_LENGTH + " bytes");
            }
            PacketBuffer preamble = new PacketBuffer(Unpooled.buffer());
            preamble.func_180714_a(channel);
            preamble.writeByte(parts);
            preamble.writeInt(data.length);
            ret.add(new S3FPacketCustomPayload("FML|MP", preamble));

            int offset = 0;
            for (int x = 0; x < parts; x++)
            {
                int length = Math.min(PART_SIZE, data.length - offset + 1);
                byte[] tmp = new byte[length];
                tmp[0] = (byte)(x & 0xFF);
                System.arraycopy(data, offset, tmp, 1, tmp.length - 1);
                offset += tmp.length - 1;
                ret.add(new S3FPacketCustomPayload("FML|MP", new PacketBuffer(Unpooled.wrappedBuffer(tmp))));
            }
        }
        return ret;
    }

    public void setTarget(Side target)
    {
        this.target = target;
    }

    public void setDispatcher(NetworkDispatcher networkDispatcher)
    {
        this.dispatcher = networkDispatcher;
    }

    public NetworkManager getOrigin()
    {
        return this.dispatcher != null ? this.dispatcher.manager : null;
    }

    public NetworkDispatcher getDispatcher()
    {
        return this.dispatcher;
    }

    public Side getTarget()
    {
        return target;
    }
}
