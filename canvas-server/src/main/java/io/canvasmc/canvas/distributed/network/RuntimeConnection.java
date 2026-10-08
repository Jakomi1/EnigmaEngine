package io.canvasmc.canvas.distributed.network;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class RuntimeConnection {

    private static final Logger LOGGER = LoggerFactory.getLogger("EnigmaRuntimeConnection");
    private static final AttributeKey<RuntimeConnection> CONNECTION_ATTR = AttributeKey.valueOf("enigmaConnection");
    private static final int HEARTBEAT_IDLE_SECONDS = 15;

    private final String name;
    private final EventLoopGroup eventLoopGroup;
    private final Channel channel;
    private final boolean listener;
    private final boolean ownsEventLoopGroup;
    private final long localInstanceId;

    private final Map<Long, CompletableFuture<MessageEnvelope>> pendingRequests = new ConcurrentHashMap<>();
    private final AtomicLong sequenceCounter = new AtomicLong();
    private final AtomicLong requestCounter = new AtomicLong();

    private final List<RuntimeConnection> childConnections = new CopyOnWriteArrayList<>();

    private volatile long remoteInstanceId = -1;
    private volatile BiConsumer<RuntimeConnection, MessageEnvelope> messageHandler;
    /** Invoked from {@link #close()} so callers can react to a peer going away. */
    private volatile @org.jspecify.annotations.Nullable Consumer<RuntimeConnection> closeListener;
    private volatile boolean closing = false;

    private RuntimeConnection(
            final String name,
            final EventLoopGroup eventLoopGroup,
            final Channel channel,
            final boolean listener,
            final boolean ownsEventLoopGroup,
            final long localInstanceId
    ) {
        this.name = name;
        this.eventLoopGroup = eventLoopGroup;
        this.channel = channel;
        this.listener = listener;
        this.ownsEventLoopGroup = ownsEventLoopGroup;
        this.localInstanceId = localInstanceId;
    }

    public static CompletableFuture<RuntimeConnection> connect(
            final String name,
            final InetSocketAddress address,
            final long localInstanceId
    ) {
        final NioEventLoopGroup group = new NioEventLoopGroup(1, r -> {
            final Thread t = new Thread(r, "EnigmaNetty-" + name + "-Client");
            t.setDaemon(true);
            return t;
        });

        final Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                .handler(new ChannelInitializer<NioSocketChannel>() {
                    @Override
                    protected void initChannel(final NioSocketChannel ch) {
                        addFramePipeline(ch, new ClientHandler(name));
                    }
                });

        final CompletableFuture<RuntimeConnection> future = new CompletableFuture<>();
        bootstrap.connect(address).addListener((ChannelFuture f) -> {
            if (!f.isSuccess()) {
                group.shutdownGracefully();
                future.completeExceptionally(f.cause());
                return;
            }
            final Channel channel = f.channel();
            final RuntimeConnection conn = new RuntimeConnection(name, group, channel, false, true, localInstanceId);
            channel.attr(CONNECTION_ATTR).set(conn);
            future.complete(conn);
        });
        return future;
    }

    public static CompletableFuture<RuntimeConnection> bind(
            final String name,
            final InetSocketAddress address,
            final long localInstanceId,
            final Consumer<RuntimeConnection> onNewConnection
    ) {
        final NioEventLoopGroup bossGroup = new NioEventLoopGroup(1, r -> {
            final Thread t = new Thread(r, "EnigmaNetty-" + name + "-Boss");
            t.setDaemon(true);
            return t;
        });
        final NioEventLoopGroup workerGroup = new NioEventLoopGroup(0, r -> {
            final Thread t = new Thread(r, "EnigmaNetty-" + name + "-Worker");
            t.setDaemon(true);
            return t;
        });

        final AtomicReference<RuntimeConnection> listenerRef = new AtomicReference<>();

        final ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ChannelInitializer<NioServerSocketChannel>() {
                    @Override
                    protected void initChannel(final NioServerSocketChannel ch) {
                        ch.pipeline().addLast("listenerClose",
                                new io.netty.channel.ChannelInboundHandlerAdapter() {
                                    @Override
                                    public void channelInactive(final ChannelHandlerContext ctx) {
                                        LOGGER.info("Runtime listener closed, shutting down event loops");
                                        bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
                                        workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
                                        ctx.fireChannelInactive();
                                    }
                                });
                    }
                })
                .childHandler(new ChannelInitializer<NioSocketChannel>() {
                    @Override
                    protected void initChannel(final NioSocketChannel ch) {
                        addFramePipeline(ch, new ServerHandler(workerGroup, onNewConnection, listenerRef.get()));
                    }
                });

        final CompletableFuture<RuntimeConnection> future = new CompletableFuture<>();
        bootstrap.bind(address).addListener((ChannelFuture f) -> {
            if (!f.isSuccess()) {
                bossGroup.shutdownGracefully();
                workerGroup.shutdownGracefully();
                future.completeExceptionally(f.cause());
                return;
            }
            final Channel channel = f.channel();
            // ownsEventLoopGroup is false: the boss/worker groups are torn down by listenerClose.
            final RuntimeConnection conn = new RuntimeConnection(name, workerGroup, channel, true, false, localInstanceId);
            channel.attr(CONNECTION_ATTR).set(conn);
            listenerRef.set(conn);
            future.complete(conn);
        });
        return future;
    }

    private static void addFramePipeline(final Channel ch, final SimpleChannelInboundHandler<MessageEnvelope> handler) {
        final ChannelPipeline pipeline = ch.pipeline();
        pipeline.addLast("idle", new IdleStateHandler(0, 0, HEARTBEAT_IDLE_SECONDS, TimeUnit.SECONDS));
        pipeline.addLast("frameDecoder", new MessageFrameDecoder());
        pipeline.addLast("frameEncoder", new MessageFrameEncoder());
        pipeline.addLast("handler", handler);
    }

    /**
     * Installs the inbound message handler. When this connection is a listener, the handler is
     * propagated to every already-accepted child connection and to all future ones, which is the
     * only way messages sent by a Compute Host can ever reach the World Host.
     */
    public void setMessageHandler(final BiConsumer<RuntimeConnection, MessageEnvelope> handler) {
        this.messageHandler = handler;
        if (this.listener) {
            for (final RuntimeConnection child : this.childConnections) {
                child.setMessageHandler(handler);
            }
        }
    }

    public CompletableFuture<MessageEnvelope> sendAndWait(final MessageEnvelope message, final long timeoutMs) {
        final long requestId = requestCounter.incrementAndGet();
        final MessageEnvelope request = message.withRequestId(requestId);

        final CompletableFuture<MessageEnvelope> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);

        final ByteBuf payload = request.getPayload();
        channel.writeAndFlush(request).addListener((ChannelFuture f) -> {
            ReferenceCountUtil.release(payload);
            if (!f.isSuccess()) {
                pendingRequests.remove(requestId);
                future.completeExceptionally(f.cause());
            }
        });

        return future.orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .whenComplete((_, _) -> pendingRequests.remove(requestId));
    }

    public void send(final MessageEnvelope message) {
        if (!ensureSendable()) {
            ReferenceCountUtil.release(message.getPayload());
            return;
        }
        final MessageEnvelope stamped = message.withSequenceNumber(sequenceCounter.incrementAndGet());
        final ByteBuf payload = stamped.getPayload();
        channel.writeAndFlush(stamped).addListener((ChannelFuture f) -> {
            ReferenceCountUtil.release(payload);
            if (!f.isSuccess()) {
                LOGGER.error("{} failed to send {} to {}", name, message.getMessageType(),
                        channel.remoteAddress(), f.cause());
            }
        });
    }

    public void sendControl(final MessageEnvelope.MessageType type, final ByteBuf payload) {
        send(MessageEnvelope.createControl(type, payload));
    }

    /**
     * Replies to a request, echoing its correlation id so the caller's pending future completes.
     * The reply is fire-and-forget: there is nothing useful to do with a failure to deliver it.
     */
    public void sendReply(final MessageEnvelope request, final MessageEnvelope.MessageType type,
                          final ByteBuf payload) {
        if (!ensureSendable()) {
            ReferenceCountUtil.release(payload);
            return;
        }
        final MessageEnvelope reply = MessageEnvelope
                .create(request.getGeneration(), request.getLeaseId(), request.getFenceToken(), 0,
                        request.getRequestId(), type, payload);
        final ByteBuf toRelease = reply.getPayload();
        channel.writeAndFlush(reply).addListener((ChannelFuture f) -> {
            ReferenceCountUtil.release(toRelease);
            if (!f.isSuccess()) {
                LOGGER.error("{} failed to send reply {} to {}", name, type, channel.remoteAddress(), f.cause());
            }
        });
    }

    private boolean ensureSendable() {
        if (closing || !channel.isActive() || !channel.isWritable()) {
            LOGGER.error("{} cannot send: connection is not usable (active={}, writable={}, closing={})",
                    name, channel.isActive(), channel.isWritable(), closing);
            return false;
        }
        if (listener) {
            LOGGER.error("{} is a listener socket and cannot send control messages;"
                    + " use the accepted child connection instead", name);
            return false;
        }
        return true;
    }

    public void completePending(final long requestId, final MessageEnvelope response) {
        final CompletableFuture<MessageEnvelope> future = pendingRequests.remove(requestId);
        if (future != null) {
            // The dispatcher releases the inbound payload as soon as handleMessage returns, so a
            // reply is only readable while its dependents run synchronously inside complete().
            // Dependents attached to an already-completed wrapper stage (orTimeout/whenComplete)
            // can run after that and would otherwise read a refCnt-0 buffer. Retain so the reply
            // stays alive; the reader releases it once it is done with the ack.
            response.getPayload().retain();
            future.complete(response);
        }
    }

    public void close() {
        closing = true;
        final Consumer<RuntimeConnection> listener = this.closeListener;
        if (listener != null) {
            try {
                listener.accept(this);
            } catch (final Throwable t) {
                LOGGER.error("Close listener of {} failed", name, t);
            }
        }
        for (final RuntimeConnection child : childConnections) {
            child.close();
        }
        childConnections.clear();
        for (final CompletableFuture<MessageEnvelope> pending : pendingRequests.values()) {
            pending.completeExceptionally(new java.io.IOException("Connection " + name + " closed"));
        }
        pendingRequests.clear();
        channel.close();
        if (this.ownsEventLoopGroup) {
            eventLoopGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        }
    }

    public boolean isOpen() {
        return !closing && channel.isOpen();
    }

    public boolean isListener() {
        return listener;
    }

    public void setCloseListener(final @org.jspecify.annotations.Nullable Consumer<RuntimeConnection> closeListener) {
        this.closeListener = closeListener;
    }

    public String getName() {
        return name;
    }

    public Channel getChannel() {
        return channel;
    }

    public long getLocalInstanceId() {
        return localInstanceId;
    }

    public long getRemoteInstanceId() {
        return remoteInstanceId;
    }

    public void setRemoteInstanceId(final long remoteInstanceId) {
        this.remoteInstanceId = remoteInstanceId;
    }

    /**
     * Handles one inbound frame.
     *
     * <p>Ownership: {@code message.getPayload()} is a retained slice owned by this method. Handlers
     * must read it and must <em>not</em> release it; the dispatcher releases it once the handler
     * returned.</p>
     *
     * <p>A reply is anything that echoes a request id we are still waiting on. Its payload is only
     * guaranteed to be readable for the duration of {@code future.complete(...)}; completions run
     * their dependents synchronously, which is what every caller in this package relies on.</p>
     */
    private void handleMessage(final MessageEnvelope message) {
        if (message.getRequestId() != 0 && pendingRequests.containsKey(message.getRequestId())) {
            completePending(message.getRequestId(), message);
            return;
        }
        final BiConsumer<RuntimeConnection, MessageEnvelope> handler = this.messageHandler;
        if (handler != null) {
            try {
                handler.accept(this, message);
            } catch (final Throwable t) {
                // One bad frame must not take the whole distributed link down; log it (with the
                // message identity so the culprit is identifiable) and keep the channel open.
                LOGGER.error("{} failed to handle {} (requestId={}, payload={} bytes): {}",
                        name, message.getMessageType(), message.getRequestId(),
                        message.getPayload().readableBytes(), t);
            }
        } else {
            LOGGER.warn("{} received {} with no handler installed", name, message.getMessageType());
        }
    }

    private static class MessageFrameDecoder extends ByteToMessageDecoder {
        @Override
        protected void decode(final ChannelHandlerContext ctx, final ByteBuf in, final List<Object> out) {
            final MessageEnvelope envelope = MessageEnvelope.decode(in);
            if (envelope == null) {
                // Either a partial frame or a corrupt header. Distinguish by peeking the magic,
                // which is only safe to interpret once at least two bytes are available.
                if (in.readableBytes() >= 2 && in.getShort(in.readerIndex()) != MessageEnvelope.MAGIC) {
                    LOGGER.error("Received frame with bad magic 0x{}, closing connection", in.getShort(in.readerIndex()));
                    ctx.close();
                }
                return;
            }
            out.add(envelope);
        }
    }

    private static class MessageFrameEncoder extends MessageToByteEncoder<MessageEnvelope> {
        @Override
        protected void encode(final ChannelHandlerContext ctx, final MessageEnvelope msg, final ByteBuf out) {
            msg.encode(out);
        }
    }

    private abstract static class BaseHandler extends SimpleChannelInboundHandler<MessageEnvelope> {
        protected void dispatch(final ChannelHandlerContext ctx, final MessageEnvelope msg) {
            final RuntimeConnection conn = ctx.channel().attr(CONNECTION_ATTR).get();
            if (conn == null) {
                // MessageEnvelope is not ReferenceCounted, so the retained slice has to be released
                // explicitly.
                ReferenceCountUtil.release(msg.getPayload());
                return;
            }
            try {
                conn.handleMessage(msg);
            } finally {
                // decode() handed us a retained slice; the handler was done with it by now.
                ReferenceCountUtil.release(msg.getPayload());
            }
        }

        @Override
        public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt) throws Exception {
            if (evt instanceof IdleStateEvent) {
                final RuntimeConnection conn = ctx.channel().attr(CONNECTION_ATTR).get();
                if (conn != null && !conn.listener && conn.isOpen()) {
                    conn.sendControl(MessageEnvelope.MessageType.HEARTBEAT, io.netty.buffer.Unpooled.EMPTY_BUFFER);
                }
            } else {
                super.userEventTriggered(ctx, evt);
            }
        }

        @Override
        public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
            LOGGER.error("Connection handler error on {}", ctx.channel(), cause);
            ctx.close();
        }
    }

    private static class ClientHandler extends BaseHandler {
        private final String connectionName;

        ClientHandler(final String connectionName) {
            this.connectionName = connectionName;
        }

        @Override
        protected void channelRead0(final ChannelHandlerContext ctx, final MessageEnvelope msg) {
            dispatch(ctx, msg);
        }

        @Override
        public void channelInactive(final ChannelHandlerContext ctx) {
            LOGGER.info("{} disconnected from {}", connectionName, ctx.channel().remoteAddress());
            final RuntimeConnection conn = ctx.channel().attr(CONNECTION_ATTR).get();
            if (conn != null && !conn.closing) {
                conn.close();
            }
        }
    }

    private static class ServerHandler extends BaseHandler {
        private final NioEventLoopGroup workerGroup;
        private final Consumer<RuntimeConnection> onNewConnection;
        private final @Nullable RuntimeConnection listener;

        ServerHandler(
                final NioEventLoopGroup workerGroup,
                final Consumer<RuntimeConnection> onNewConnection,
                final @Nullable RuntimeConnection listener
        ) {
            this.workerGroup = workerGroup;
            this.onNewConnection = onNewConnection;
            this.listener = listener;
        }

        @Override
        public void channelActive(final ChannelHandlerContext ctx) {
            final RuntimeConnection conn = new RuntimeConnection(
                    "peer-" + ctx.channel().remoteAddress(),
                    workerGroup,
                    ctx.channel(),
                    false,
                    false,
                    -1
            );
            ctx.channel().attr(CONNECTION_ATTR).set(conn);

            if (this.listener != null) {
                // Register with the listener so the handler is installed on this connection and
                // so closing the listener tears the peer down as well.
                this.listener.childConnections.add(conn);
                final BiConsumer<RuntimeConnection, MessageEnvelope> handler = this.listener.messageHandler;
                if (handler != null) {
                    conn.setMessageHandler(handler);
                }
            }

            onNewConnection.accept(conn);
        }

        @Override
        protected void channelRead0(final ChannelHandlerContext ctx, final MessageEnvelope msg) {
            dispatch(ctx, msg);
        }

        @Override
        public void channelInactive(final ChannelHandlerContext ctx) {
            final RuntimeConnection conn = ctx.channel().attr(CONNECTION_ATTR).get();
            LOGGER.info("Peer connection {} closed (instanceId={})",
                    ctx.channel().remoteAddress(), conn == null ? "?" : conn.getRemoteInstanceId());
            if (conn != null) {
                conn.close();
            }
        }
    }
}
