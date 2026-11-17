package pyzh.harness;

import com.mojang.authlib.GameProfile;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.IoHandle;
import io.netty.channel.IoHandler;
import io.netty.channel.IoHandlerContext;
import io.netty.channel.IoRegistration;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalIoHandler;
import io.netty.channel.local.LocalServerChannel;
import io.netty.util.ReferenceCountUtil;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.util.Mth;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Fake players: a genuine ServerPlayer joined through PlayerList.placeNewPlayer on a real Connection whose
 * channel is an in-JVM netty LocalChannel. Outbound packets are discarded on the netty thread; the few packets
 * a client must answer (keepalive, teleport, death, chunk batch) are answered by calling the real serverbound
 * handlers on the main thread. Movement is injected as ServerboundMovePlayerPacket, like a real client.
 */
final class Bots {
    private static final ClientInformation CLIENT =
        new ClientInformation("en_us", 16, ChatVisiblity.FULL, true, 0x7f, HumanoidArm.RIGHT, false, true, ParticleStatus.ALL);

    interface Mover {
        /** Sets bot.nx/ny/nz/nyaw/nOnGround and returns true when the bot moves this tick. */
        boolean step(Bot bot, ServerLevel level, int tick);
    }

    final class Bot {
        final String name;
        final GameMode mode;
        final double homeX, homeZ;
        ServerGamePacketListenerImpl listener;
        Mover mover;
        double x, y, z;
        float yaw;
        double nx, ny, nz;
        float nyaw;
        boolean nOnGround = true;
        /** Moves the server refused and answered with a teleport back; movers treat a change as an obstacle. */
        int rejected;
        private int expectedTeleports;
        private Location relocation;
        private boolean moved;
        private boolean loaded;
        private int sinceMove;

        Bot(String name, GameMode mode, double x, double y, double z) {
            this.name = name;
            this.mode = mode;
            this.homeX = this.x = x;
            this.y = y;
            this.homeZ = this.z = z;
        }

        ServerPlayer player() {
            return listener.player;
        }

        Player bukkit() {
            return listener.player.getBukkitEntity();
        }

        /**
         * Server-side relocation, answered like a client would (ack + position echo). It is issued after this
         * tick's packets so the ack is handled a tick later, as it would be over a network.
         */
        void teleport(double tx, double ty, double tz) {
            relocation = new Location(bukkit().getWorld(), tx, ty, tz, yaw, 0f);
        }

        private void onTeleport(int id) {
            ServerPlayer p = player();
            if (expectedTeleports > 0) {
                expectedTeleports--;
            } else if (loaded) {
                rejected++;
                if (rejectedMoves++ < 5) {
                    Bukkit.getLogger().warning("harness: server rejected move of " + name + " from " + p.getX() + "," + p.getY() + "," + p.getZ()
                        + " to " + x + "," + y + "," + z + " (feet " + bukkit().getWorld().getBlockAt(Mth.floor(x), Mth.floor(y), Mth.floor(z)).getType() + ")");
                }
            }
            listener.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(id));
            x = p.getX();
            y = p.getY();
            z = p.getZ();
            if (!loaded) {
                loaded = true;
                listener.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            }
            listener.handleMovePlayer(new ServerboundMovePlayerPacket.PosRot(x, y, z, yaw, 0f, false, false));
        }

        private void onDeath() {
            deaths++;
            listener.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
            equip(this);
            teleport(x, y, z);
        }
    }

    final List<Bot> all = new ArrayList<>();
    int relocations;
    int deaths;
    int rejectedMoves;

    private final MinecraftServer server = MinecraftServer.getServer();
    /**
     * Same loop class and task queue as the server's own IO loops. A real loop under load is busy encoding and
     * is already awake when the main thread hands it a packet; this one has nothing to do and would be parked
     * every time, so each send would pay a thread wake-up. It polls on a short timer instead and never needs waking.
     */
    private final MultiThreadIoEventLoopGroup loops = new MultiThreadIoEventLoopGroup(1, runnable -> {
        Thread thread = new Thread(runnable, "harness-fake-netty");
        thread.setDaemon(true);
        return thread;
    }, executor -> new PollingIoHandler(LocalIoHandler.newFactory().newHandler(executor)));

    private record PollingIoHandler(IoHandler local) implements IoHandler {
        @Override
        public int run(IoHandlerContext context) {
            if (context.canBlock()) {
                LockSupport.parkNanos(50_000);
            }
            return 0;
        }

        @Override
        public void wakeup() {
        }

        @Override
        public IoRegistration register(IoHandle handle) throws Exception {
            return local.register(handle);
        }

        @Override
        public boolean isCompatible(Class<? extends IoHandle> handleType) {
            return local.isCompatible(handleType);
        }

        @Override
        public void prepareToDestroy() {
            local.prepareToDestroy();
        }

        @Override
        public void destroy() {
            local.destroy();
        }
    }
    private final LocalAddress address = new LocalAddress("pyzh-perf-harness");
    private final BlockingQueue<Sink> accepted = new LinkedBlockingQueue<>();
    private final Queue<Runnable> inbox = new ConcurrentLinkedQueue<>();

    Bots() {
        new ServerBootstrap().group(loops).channel(LocalServerChannel.class).childHandler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel channel) {
                Sink sink = new Sink(new Connection(PacketFlow.SERVERBOUND));
                channel.pipeline().addLast("harness_sink", sink).addLast("packet_handler", sink.connection);
            }
        }).bind(address).syncUninterruptibly();
    }

    private final class Sink extends ChannelDuplexHandler {
        final Connection connection;
        volatile Bot bot;

        Sink(Connection connection) {
            this.connection = connection;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            ctx.fireChannelActive();
            accepted.add(this);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            Bot b = bot;
            if (b != null) {
                switch (msg) {
                    case ClientboundKeepAlivePacket packet ->
                        inbox.add(() -> b.listener.handleKeepAlive(new ServerboundKeepAlivePacket(packet.getId())));
                    case ClientboundPlayerPositionPacket packet -> inbox.add(() -> b.onTeleport(packet.id()));
                    case ClientboundPlayerCombatKillPacket packet -> inbox.add(b::onDeath);
                    case ClientboundChunkBatchFinishedPacket packet ->
                        inbox.add(() -> b.listener.handleChunkBatchReceived(new ServerboundChunkBatchReceivedPacket(64f)));
                    default -> {
                    }
                }
            }
            ReferenceCountUtil.release(msg);
            promise.trySuccess();
        }

        @Override
        public void flush(ChannelHandlerContext ctx) {
        }
    }

    Bot join(String name, World world, double x, double y, double z, GameMode mode) {
        new Bootstrap().group(loops).channel(LocalChannel.class).handler(new ChannelInboundHandlerAdapter()).connect(address).syncUninterruptibly();
        Sink sink;
        try {
            sink = accepted.poll(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        if (sink == null) {
            throw new IllegalStateException("fake connection was not accepted");
        }
        Connection connection = sink.connection;
        connection.address = new InetSocketAddress(InetAddress.getLoopbackAddress(), 30000 + all.size());
        connection.isPending = false;

        ServerLevel level = ((CraftWorld) world).getHandle();
        GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)), name);
        ServerPlayer player = new ServerPlayer(server, level, profile, CLIENT);
        player.snapTo(x, y, z, 0f, 0f);
        Bot bot = new Bot(name, mode, x, y, z);
        sink.bot = bot;
        server.getConnection().getConnections().add(connection);
        server.getPlayerList().placeNewPlayer(connection, player, CommonListenerCookie.createInitial(profile, false));
        bot.listener = player.connection;
        equip(bot);
        all.add(bot);
        return bot;
    }

    /** Survival bots stay targetable by mobs but cannot die: full damage resistance and no hunger. */
    private void equip(Bot bot) {
        Player player = bot.bukkit();
        player.setGameMode(bot.mode);
        if (bot.mode == GameMode.SURVIVAL) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, PotionEffect.INFINITE_DURATION, 4, false, false));
            player.addPotionEffect(new PotionEffect(PotionEffectType.SATURATION, PotionEffect.INFINITE_DURATION, 0, false, false));
        } else {
            player.setAllowFlight(true);
            player.setFlying(true);
        }
    }

    /**
     * Answers pending clientbound packets through the real serverbound handlers. Runs before path planning so a
     * teleport is acknowledged before the next move is computed; its cost lands in the previous tick's span.
     */
    void drainInbox() {
        Runnable task;
        while ((task = inbox.poll()) != null) {
            task.run();
        }
    }

    /** Harness-side path computation; excluded from the measured tick. */
    void plan(int tick) {
        for (Bot bot : all) {
            ServerPlayer player = bot.player();
            if (bot.mover == null) {
                bot.moved = false;
                continue;
            }
            bot.moved = bot.mover.step(bot, player.level(), tick);
        }
    }

    /** What a client sends each tick: a move packet when it moved (or once a second when idle) and tick-end. */
    void send() {
        for (Bot bot : all) {
            ServerGamePacketListenerImpl listener = bot.listener;
            if (bot.moved) {
                bot.x = bot.nx;
                bot.y = bot.ny;
                bot.z = bot.nz;
                bot.yaw = bot.nyaw;
                bot.sinceMove = 0;
                listener.handleMovePlayer(new ServerboundMovePlayerPacket.PosRot(bot.x, bot.y, bot.z, bot.yaw, 0f, bot.nOnGround, false));
            } else if (++bot.sinceMove >= 20) {
                bot.sinceMove = 0;
                listener.handleMovePlayer(new ServerboundMovePlayerPacket.Pos(bot.x, bot.y, bot.z, bot.nOnGround, false));
            }
            listener.handleClientTickEnd(ServerboundClientTickEndPacket.INSTANCE);
        }
        for (Bot bot : all) {
            if (bot.relocation != null) {
                relocations++;
                bot.expectedTeleports++;
                bot.bukkit().teleport(bot.relocation);
                bot.relocation = null;
            }
        }
    }

    boolean allLoaded() {
        for (Bot bot : all) {
            if (!bot.loaded) {
                return false;
            }
        }
        return true;
    }

    void shutdown() {
        loops.shutdownGracefully(0, 1, TimeUnit.SECONDS);
    }
}
