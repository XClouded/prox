/*
 * Copyright (C) 2015 XiNGRZ <chenxingyu92@gmail.com>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package me.xingrz.prox.udp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.concurrent.TimeUnit;

import me.xingrz.prox.ProxVpnService;
import me.xingrz.prox.logging.FormattingLogger;
import me.xingrz.prox.logging.FormattingLoggers;
import me.xingrz.prox.transport.AbstractTransportProxy;

public class UdpProxy extends AbstractTransportProxy<DatagramChannel, DatagramChannel, UdpProxySession> {

    private static final int UDP_SESSION_MAX_COUNT = 20;
    private static final long UDP_SESSION_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(30);

    private final ByteBuffer buffer = ByteBuffer.allocate(0xFFFF);

    public UdpProxy() throws IOException {
        super(UDP_SESSION_MAX_COUNT, UDP_SESSION_TIMEOUT_MS);
    }

    @Override
    protected DatagramChannel createChannel(Selector selector) throws IOException {
        DatagramChannel channel = DatagramChannel.open();
        channel.configureBlocking(false);
        channel.socket().bind(new InetSocketAddress(0));
        channel.register(selector, SelectionKey.OP_READ, this);
        return channel;
    }

    @Override
    protected FormattingLogger getLogger() {
        return FormattingLoggers.getContextLogger();
    }

    @Override
    public int port() {
        return serverChannel.socket().getLocalPort();
    }

    @Override
    protected void onSelected(SelectionKey key) {
        try {
            if (key.isReadable()) {
                if (key.attachment() != null && key.attachment() instanceof UdpProxySession) {
                    receive((DatagramChannel) key.channel(), (UdpProxySession) key.attachment());
                } else {
                    accept((DatagramChannel) key.channel());
                }
            }
        } catch (IOException e) {
            logger.w(e, "Proxy faced an error");
        }
    }

    @Override
    protected UdpProxySession createSession(int sourcePort, InetAddress remoteAddress, int remotePort)
            throws IOException {
        return new UdpProxySession(selector, sourcePort, remoteAddress, remotePort);
    }

    /**
     * 将 UDP 包发到公网，并等待同一个端口的返回
     *
     * @param sourcePort    本地来源端口，作为识别
     * @param remoteAddress 目标地址
     * @param remotePort    目标端口
     * @return 所创建的会话
     * @throws IOException
     */
    @Override
    public UdpProxySession pickSession(int sourcePort, InetAddress remoteAddress, int remotePort) throws IOException {
        UdpProxySession session = super.pickSession(sourcePort, remoteAddress, remotePort);

        logger.v("Created session %08x local:%d -> in:%d -> out:%d -> %s:%d",
                session.hashCode(),
                sourcePort,
                port(),
                session.socket().getLocalPort(),
                session.getRemoteAddress().getHostAddress(), session.getRemotePort());

        return session;
    }

    /**
     * 收到了刚才 VPN 网关 {@link #createSession(int, java.net.InetAddress, int)} 过后的转发数据包
     * 那么我们就将它发到该去的地方吧！
     *
     * @param localChannel 代表网关的通道
     * @throws IOException
     */
    public void accept(DatagramChannel localChannel) throws IOException {
        buffer.clear();

        InetSocketAddress source = (InetSocketAddress) localChannel.receive(buffer);
        if (source == null) {
            logger.w("Ignored packet without source");
            return;
        }

        UdpProxySession session = getSession(source.getPort());
        if (session == null) {
            logger.w("Ignored packet from %d without session", source.getPort());
            return;
        }

        logger.v("Accepted channel from %d, session %08x", source.getPort(), session.hashCode());

        buffer.flip();

        session.send(buffer);

        logger.v("Sent out session %08x local:%d -> in:%d -> out:%d -> %s:%d",
                session.hashCode(),
                session.getSourcePort(),
                port(),
                session.socket().getLocalPort(),
                session.getRemoteAddress().getHostAddress(), session.getRemotePort());
    }

    /**
     * 将公网的 UDP 返回反哺回给 VPN 网关
     *
     * @param remoteChannel 公网通道
     * @param session       对应的会话
     * @throws IOException
     */
    private void receive(DatagramChannel remoteChannel, UdpProxySession session) throws IOException {
        buffer.clear();

        remoteChannel.receive(buffer);

        buffer.flip();

        session.finish();

        InetSocketAddress address = new InetSocketAddress(
                ProxVpnService.FAKE_CLIENT_ADDRESS, session.getSourcePort());

        logger.v("Received in session %08x %s:%d <- in:%d <- out:%d <- %s:%d",
                session.hashCode(),
                address.getHostString(), session.getSourcePort(),
                port(),
                session.socket().getLocalPort(),
                session.getRemoteAddress().getHostAddress(), session.getRemotePort());

        serverChannel.send(buffer, address);
    }

    /**
     * 完成会话
     *
     * @param sourcePort 本地来源端口，作为识别
     * @return 会话对象
     */
    @Override
    public UdpProxySession finishSession(int sourcePort) {
        UdpProxySession session = super.finishSession(sourcePort);
        if (session == null) {
            logger.v("No session to finish at port %d", sourcePort);
            return null;
        } else {
            logger.v("Finished session %08x", session.hashCode());
            return session;
        }
    }

}
