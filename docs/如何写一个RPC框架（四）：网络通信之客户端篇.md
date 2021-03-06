> 在后续一段时间里， 我会写一系列文章来讲述如何实现一个RPC框架。 这是系列第四篇文章， 主要讲述了客户端和服务器之间的网络通信问题。


## 模型定义
我们需要自己来定义RPC通信所传递的内容的模型， 也就是RPCRequest和RPCResponse。

```
@Data
@Builder
public class RPCRequest {
	private String requestId;
	private String interfaceName;
	private String methodName;
	private Class<?>[] parameterTypes;
	private Object[] parameters;
}

@Data
public class RPCResponse {
    private String requestId;
    private Exception exception;
    private Object result;

    public boolean hasException() {
        return exception != null;
    }
}
```

这里唯一需要说明一下的是requestId， 你可能会疑惑为什么我们需要这个东西。

原因是，发送请求的顺序和收到返回的顺序可能是不一致的， 因此我们需要有一个标识符来表明某一个返回所对应的请求是什么。 具体怎么利用这个字段， 本文后续会揭晓。


## 选择NIO还是IO？
NIO和IO的选择要视具体情况而定。对于我们的RPC框架来说， 一个服务可能与多个服务保持连接， 且每次通信只发送少量信息，那么在这种情况下，NIO可能更适合一些。

我选择使用Netty来简化具体的实现， 自然地，我们就引入了Channel, Handler这些相关的概念。如果对Netty没有任何了解， 建议先去简单了解下相关内容再回过头看这篇文章。

## 如何复用Channel
既然使用了NIO， 我们自然希望服务和服务之间是使用长连接进行通信， 而不是每个请求都重新创建一个channel。

那么我们怎么去复用channel呢？ 既然我们已经通过前文的服务发现获取到了service地址，并且与其建立了channel， 那么我们自然就可以建立一个service地址与channel之间的映射关系， 每次拿到地址之后先判断有没有对应channel， 如果有的话就复用。这种映射关系我建立了ChannelManager去管理：

```
public class ChannelManager {
	/**
	 * Singleton
	 */
	private static ChannelManager channelManager;

	private ChannelManager(){}

	public static ChannelManager getInstance() {
		if (channelManager == null) {
			synchronized (ChannelManager.class) {
				if (channelManager == null) {
					channelManager = new ChannelManager();
				}
			}
		}
		return channelManager;
	}
    
    // Service地址与channel之间的映射
	private Map<InetSocketAddress, Channel> channels = new ConcurrentHashMap<>();

	public Channel getChannel(InetSocketAddress inetSocketAddress) {
		Channel channel = channels.get(inetSocketAddress);
		if (null == channel) {
			EventLoopGroup group = new NioEventLoopGroup();
			try {
				Bootstrap bootstrap = new Bootstrap();
				bootstrap.group(group)
						.channel(NioSocketChannel.class)
						.handler(new RPCChannelInitializer())
						.option(ChannelOption.SO_KEEPALIVE, true);

				channel = bootstrap.connect(inetSocketAddress.getHostName(), inetSocketAddress.getPort()).sync()
						.channel();
				registerChannel(inetSocketAddress, channel);

				channel.closeFuture().addListener(new ChannelFutureListener() {
					@Override
					public void operationComplete(ChannelFuture future) throws Exception {
						removeChannel(inetSocketAddress);
					}
				});
			} catch (Exception e) {
				log.warn("Fail to get channel for address: {}", inetSocketAddress);
			}
		}
		return channel;
	}

	private void registerChannel(InetSocketAddress inetSocketAddress, Channel channel) {
		channels.put(inetSocketAddress, channel);
	}

	private void removeChannel(InetSocketAddress inetSocketAddress) {
		channels.remove(inetSocketAddress);
	}

}
```
有几个地方需要解释一下：

 1. 这里用单例的目的是， 所有的proxybean都使用同一个ChannelManager。
 2. 创建Channel的过程很简单，就是最普通的Netty客户端创建channel的方法。
 3. 在channel被关闭（比如服务器端宕机了）后，需要从map中删除对应的channel
 4. RPCChannelInitializer是整个过程的核心所在， 用于处理请求和返回的编解码、 收到返回之后的回调等。 下文详细说这个。


## 编解码
上文的RPCChannelInitializer代码如下：

```
private class RPCChannelInitializer extends ChannelInitializer<SocketChannel> {

		@Override
		protected void initChannel(SocketChannel ch) throws Exception {
			ChannelPipeline pipeline = ch.pipeline();
			pipeline.addLast(new RPCEncoder(RPCRequest.class, new ProtobufSerializer()));
			pipeline.addLast(new RPCDecoder(RPCResponse.class, new ProtobufSerializer()));
			pipeline.addLast(new RPCResponseHandler());  //先不用管这个
		}
	}
```
这里的Encoder和Decoder都很简单， 继承了Netty中的codec，做一些简单的byte数组和Object对象之间的转换工作：

```
@AllArgsConstructor
public class RPCDecoder extends ByteToMessageDecoder {

	private Class<?> genericClass;
	private Serializer serializer;

	@Override
	public void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
		if (in.readableBytes() < 4) {
			return;
		}
		in.markReaderIndex();
		int dataLength = in.readInt();
		if (in.readableBytes() < dataLength) {
			in.resetReaderIndex();
			return;
		}
		byte[] data = new byte[dataLength];
		in.readBytes(data);
		out.add(serializer.deserialize(data, genericClass));
	}
}

@AllArgsConstructor
public class RPCEncoder extends MessageToByteEncoder {

	private Class<?> genericClass;
	private Serializer serializer;

	@Override
	public void encode(ChannelHandlerContext ctx, Object in, ByteBuf out) throws Exception {
		if (genericClass.isInstance(in)) {
			byte[] data = serializer.serialize(in);
			out.writeInt(data.length);
			out.writeBytes(data);
		}
	}
}
```
这里我选择使用Protobuf序列化协议来做这件事（具体的ProtobufSerializer的实现因为篇幅原因就不贴在这里了， 需要的话请看项目的[github](https://github.com/hshenCode/hrpc)）。 总的来说， 这一块还是很简单很好理解的。

## 发送请求与处理返回内容
请求的发送很简单， 直接用channel.writeAndFlush(request) 就行了。

问题是， 发送之后， 怎么获取这个请求的返回呢？这里，我引入了RPCResponseFuture和ResponseFutureManager来解决这个问题。

RPCResponseFuture实现了Future接口，所包含的值就是RPCResponse， 每个RPCResponseFuture都与一个requestId相关联， 除此之外， 还利用了CountDownLatch来做get方法的阻塞处理：

```
public class RPCResponseFuture implements Future<Object> {
	private String requestId;

	private RPCResponse response;

	CountDownLatch latch = new CountDownLatch(1);

	public RPCResponseFuture(String requestId) {
		this.requestId = requestId;
	}

	public void done(RPCResponse response) {
		this.response = response;
		latch.countDown();
	}

	@Override
	public RPCResponse get() throws InterruptedException, ExecutionException {
		try {
			latch.await();
		} catch (InterruptedException e) {
			log.error(e.getMessage());
		}
		return response;
	}
  
  // ....
}
```

既然每个请求都会产生一个ResponseFuture, 那么自然要有一个Manager来管理这些future：

```
public class ResponseFutureManager {
	/**
	 * Singleton
	 */
	private static ResponseFutureManager rpcFutureManager;

	private ResponseFutureManager(){}

	public static ResponseFutureManager getInstance() {
		if (rpcFutureManager == null) {
			synchronized (ChannelManager.class) {
				if (rpcFutureManager == null) {
					rpcFutureManager = new ResponseFutureManager();
				}
			}
		}
		return rpcFutureManager;
	}

	private ConcurrentHashMap<String, RPCResponseFuture> rpcFutureMap = new ConcurrentHashMap<>();

	public void registerFuture(RPCResponseFuture rpcResponseFuture) {
		rpcFutureMap.put(rpcResponseFuture.getRequestId(), rpcResponseFuture);
	}

	public void futureDone(RPCResponse response) {
		rpcFutureMap.remove(response.getRequestId()).done(response);
	}
}
```
ResponseFutureManager很好看懂， 就是提供了注册future、完成future的接口。

现在我们再回过头看RPCChannelInitializer中的RPCResponseHandler就很好理解了： 拿到返回值， 把对应的ResponseFuture标记成done就可以了！
 
```
/**
* 处理收到返回后的回调
*/
	private class RPCResponseHandler extends SimpleChannelInboundHandler<RPCResponse> {

		@Override
		public void channelRead0(ChannelHandlerContext ctx, RPCResponse response) throws Exception {
			log.debug("Get response: {}", response);
			ResponseFutureManager.getInstance().futureDone(response);
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
			log.warn("RPC request exception: {}", cause);
		}
	}
```

## 前文的FactoryBean的逻辑填充
到这里，我们已经实现了客户端的网络通信， 现在只需要把它加到前文的FactoryBean的doInvoke方法就好了！

```
	private Object doInvoke(Object proxy, Method method, Object[] args) throws Throwable {
		String targetServiceName = type.getName();

		// Create request
		RPCRequest request = RPCRequest.builder()
				.requestId(generateRequestId(targetServiceName))
				.interfaceName(method.getDeclaringClass().getName())
				.methodName(method.getName())
				.parameters(args)
				.parameterTypes(method.getParameterTypes()).build();

		// Get service address
		InetSocketAddress serviceAddress = getServiceAddress(targetServiceName);

		// Get channel by service address
		Channel channel = ChannelManager.getInstance().getChannel(serviceAddress);
		if (null == channel) {
			throw new RuntimeException("Cann't get channel for address" + serviceAddress);
		}

		// Send request
		RPCResponse response = sendRequest(channel, request);
		if (response == null) {
			throw new RuntimeException("response is null");
		}
		if (response.hasException()) {
			throw response.getException();
		} else {
			return response.getResult();
		}
	}

	private String generateRequestId(String targetServiceName) {
		return targetServiceName + "-" + UUID.randomUUID().toString();
	}

	private InetSocketAddress getServiceAddress(String targetServiceName) {
		String serviceAddress = "";
		if (serviceDiscovery != null) {
			serviceAddress = serviceDiscovery.discover(targetServiceName);
			log.debug("Get address: {} for service: {}", serviceAddress, targetServiceName);
		}
		if (StringUtils.isEmpty(serviceAddress)) {
			throw new RuntimeException("server address is empty");
		}
		String[] array = StringUtils.split(serviceAddress, ":");
		String host = array[0];
		int port = Integer.parseInt(array[1]);
		return new InetSocketAddress(host, port);
	}

	private RPCResponse sendRequest(Channel channel, RPCRequest request) {
		log.debug("Send request, channel: {}, request: {}", channel, request);
		CountDownLatch latch = new CountDownLatch(1);
		RPCResponseFuture rpcResponseFuture = new RPCResponseFuture(request.getRequestId());
		ResponseFutureManager.getInstance().registerFuture(rpcResponseFuture);
		channel.writeAndFlush(request).addListener((ChannelFutureListener) future -> {
			log.debug("Request sent.");
			latch.countDown();
		});
		try {
			latch.await();
		} catch (InterruptedException e) {
			log.error(e.getMessage());
		}

		try {
			return rpcResponseFuture.get(1, TimeUnit.SECONDS);
		} catch (Exception e) {
			log.warn("Exception:", e);
			return null;
		}
	}
```

就这样， 一个简单的RPC客户端就实现了。 完整代码请看[我的github](https://github.com/hshenCode/hrpc)。
