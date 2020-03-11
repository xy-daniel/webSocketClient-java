import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import jdk.nashorn.internal.parser.JSONParser;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSession.Receiptable;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.Transport;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;
import org.springframework.web.socket.sockjs.frame.Jackson2SockJsMessageCodec;

public class Client {
    private final static WebSocketHttpHeaders headers = new WebSocketHttpHeaders();//请求头
    private WebSocketStompClient client = null;//stomp客户端
    private SockJsClient SockJsClient = null;//socket客户端
    private ThreadPoolTaskScheduler Ttask = null;//连接池
    private StompSession session = null;//连接会话
    private static Map<String, String> WebSocketConfig;//配置参数
    public volatile boolean RecvFlag = false;//期待的返回标志位，当收到的消息与配置中exceptionRecv相等时为true

    public static void main(String[] args) throws Exception {
        headers.add("name", "admin");
        headers.add("username", "admin");
        headers.add("password", "1");

//        String sendMsg = "{userName : 'user',chatContext : 'chatContext',planId : 783}";
        String sendMsg = "12312312312321";

        sendMsg = (args != null && args.length != 0) ? args[0] : sendMsg;

        Client myClient = new Client();

        myClient.runStompClient(myClient,
                "ws://192.168.0.45:8080/tc/stomp",
                "/topic/chat", "/app/chat", sendMsg);

        while (!myClient.RecvFlag) {
            //持续等待返回标志位为true
            Thread.sleep(3000);
        }
        //关闭所有连接终止程序
        myClient.Ttask.destroy();
        myClient.SockJsClient.stop();
        myClient.client.stop();
        myClient.session.disconnect();
        System.exit(0);
    }


    public void runStompClient(Client client, String URI, String subscribe, String send, final String sendMsg) throws ExecutionException, InterruptedException, UnsupportedEncodingException {
        //连接到对应的endpoint点上，也就是建立起websocket连接
        ListenableFuture<StompSession> f = client.connect(URI);
        //建立成功后返回一个stomp协议的会话
        StompSession stompSession = f.get();

        System.out.println("Subscribing to greeting topic using session " + stompSession);
        //绑定订阅的消息地址subscribe
        client.subscribeGreetings(subscribe, stompSession);
        //设置Receipt头，不设置无法接受返回消息
        stompSession.setAutoReceipt(true);
        //绑定发送的的地址send，注意这里使用的字节方式发送数据
//        System.out.println("装备发送消息,发送内容为:"+sendMsg);
        Receiptable rec = stompSession.send(send, sendMsg.getBytes("UTF-8"));
        //添加消息发送成功的回调
        rec.addReceiptLostTask(new Runnable() {
            public void run() {
                System.out.println("消息发送成功,发送内容为:" + sendMsg);
            }
        });
    }

    public ListenableFuture<StompSession> connect(String url) {


        Transport webSocketTransport = new WebSocketTransport(new StandardWebSocketClient());

        List<Transport> transports = Collections.singletonList(webSocketTransport);

        SockJsClient sockJsClient = new SockJsClient(transports);
        //设置对应的解码器，理论支持任意的pojo自带转json格式发送，这里只使用字节方式发送和接收数据
        sockJsClient.setMessageCodec(new Jackson2SockJsMessageCodec());

        WebSocketStompClient stompClient = new WebSocketStompClient(sockJsClient);

        stompClient.setReceiptTimeLimit(300);

        stompClient.setDefaultHeartbeat(new long[]{10000l, 10000l});

        ThreadPoolTaskScheduler task = new ThreadPoolTaskScheduler();

        task.initialize();

        stompClient.setTaskScheduler(task);

        client = stompClient;
        SockJsClient = sockJsClient;
        Ttask = task;
        return stompClient.connect(url, headers, new MyHandler(), "localhost", 8080);
    }

    public void subscribeGreetings(String url, StompSession stompSession) throws ExecutionException, InterruptedException {
        stompSession.subscribe(url, new StompFrameHandler() {
            public Type getPayloadType(StompHeaders stompHeaders) {
                return byte[].class;//设置订阅到消息用字节方式接收
            }

            public void handleFrame(StompHeaders stompHeaders, Object o) {
                String recv = null;
                try {
                    recv = new String((byte[]) o, "UTF-8");
                } catch (UnsupportedEncodingException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
                System.out.println("收到返回的消息" + recv);
                if (WebSocketConfig != null && recv.equals("exceptionRecv")) {
                    RecvFlag = true;
                } else if (recv.equals("success")) {
                    RecvFlag = true;
                }

            }
        });
    }

    private class MyHandler extends StompSessionHandlerAdapter {
        public void afterConnected(StompSession stompSession, StompHeaders stompHeaders) {
            session = stompSession;
            System.out.println("连接成功");
        }

        @Override
        public void handleTransportError(StompSession session, Throwable exception) {
            System.out.println("连接出现异常");
            exception.printStackTrace();
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            super.handleFrame(headers, payload);
            System.out.println("=========================handleFrame");
        }
    }

    private Map<String, String> readConfig() {
        Map<String, String> ConfigMap = null;
        String[] keys = {"URI", "subscribe", "send", "exceptionRecv"};
        //D:\\dkWorkSpace\\Java\\SocketGettingStart\\StompClient\\WebSocketConfig.properties
        File file = new File("src/resource/WebSocketConfig.properties");
        if (file.exists()) {
            System.out.println("开始读取配置文件");
            ConfigMap = new HashMap<String, String>();
            FileInputStream FIS = null;
            InputStreamReader ISReader = null;
            BufferedReader reader = null;
            try {
                FIS = new FileInputStream(file);
                ISReader = new InputStreamReader(FIS, "UTF-8");
                reader = new BufferedReader(ISReader);
                String readline = null;
                System.out.println("开始按行读取配置文件");
                while ((readline = reader.readLine()) != null) {
                    System.out.println("当前行内容：" + readline);
                    String readStr[] = readline.split("=");
                    if (readStr == null || readStr.length != 2) {
                        System.out.println("配置文件格式不符合规范，必须一行一个配置，并用‘=’分割，当前行内容：" + readline);
                    }
                    ConfigMap.put(readStr[0], readStr[1]);
                }
                System.out.println("文件读取完成,最终的配置信息：" + ConfigMap);
                boolean notice = false;
                for (int i = 0; i < keys.length; i++) {
                    if (!ConfigMap.containsKey(keys[i])) {
                        System.out.println("缺少对关键参数：" + keys[i] + "的配置，配置将无法生效");
                        notice = true;
                    }
                }
                ConfigMap = notice ? null : ConfigMap;
            } catch (Exception e) {
                System.out.println("文件读取过程发生异常：" + e.getMessage());
            } finally {
                if (reader != null) {
                    try {
                        FIS.close();
                        ISReader.close();
                        reader.close();
                    } catch (IOException e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }
                }
            }
        } else {
            System.out.println("不存在配置文件，请检查路径：");
            System.out.println("开始使用默认socketConfig");
        }

        return ConfigMap;
    }
}