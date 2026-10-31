package cube.service.test;

import cell.core.talk.TalkContext;
import cell.core.talk.dialect.ActionDialect;
import cube.aigc.SpecializedDetectionException;
import cube.auth.AuthToken;
import cube.common.Packet;
import cube.common.entity.*;
import cube.service.aigc.*;
import cube.service.aigc.event.Event;
import cube.service.aigc.event.EventCenter;
import cube.service.aigc.listener.SpecializedDetectionListener;
import cube.service.aigc.unit.*;
import org.json.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/**
 * 专用检测的服务端测试。基于 JDK 8，用模拟的 Python 连接驱动真实的服务准入、线程池与请求元数据。
 */
public final class SpecializedDetectionTest {
    /** 断言计数。 */
    static int assertions;
    /** 合法的 64 位文件码。 */
    static final String FILE = String.join("", Collections.nCopies(64, "a"));

    /**
     * 测试入口，依次执行各用例组。
     *
     * @param args 未使用
     * @throws Exception 用例执行失败时抛出
     */
    public static void main(String[] args) throws Exception {
        validation(); defaults(); images(); streams(); reservationAndQueues(); timeoutAndLateReply(); retention(); configurationAndFailures();
        System.out.println("SpecializedDetectionTest: " + assertions + " assertions passed");
    }

    /**
     * 校验输入参数的边界：非法操作、非归属文件、切片推理约束等。
     */
    static void validation() {
        for (String profile : Arrays.asList("fire-smoke", "uav-fire-smoke", "future-profile"))
            check(new SpecializedDetectionInput(image(profile)).operation.equals("detect"), "default operation");
        for (JSONObject value : Arrays.asList(
                image(""), image("fire-smoke").put("files", new JSONArray().put("C:/private/image.png")),
                image("fire-smoke").put("files", new JSONArray().put(new JSONObject().put("fileCode", FILE))),
                image("fire-smoke").put("files", new JSONArray().put("https://example.org/a.png")),
                image("fire-smoke").put("option", new JSONObject().put("profile", "fire-smoke").put("sliced", true)),
                image("fire-smoke").put("option", new JSONObject().put("profile", "fire-smoke").put("confidence", 1.01)),
                control("update", "x"), control("status", "x").put("files", new JSONArray().put(FILE)),
                start().put("option", new JSONObject().put("operation", "start").put("profile", "fire-smoke"))
        )) expect(1, () -> new SpecializedDetectionInput(value));
        JSONObject source = image("uav-fire-smoke");
        SpecializedDetectionInput copy = new SpecializedDetectionInput(source);
        source.getJSONObject("option").put("profile", "wrong");
        check(copy.toJSON().getJSONObject("option").getString("profile").equals("uav-fire-smoke"), "input copied");
    }

    /**
     * 校验默认准入预算、执行器线程与队列容量，以及配置重载后的默认值。
     *
     * @throws Exception 用例执行失败时抛出
     */
    static void defaults() throws Exception {
        try (Fixture f = new Fixture(1)) {
            for (int i = 0; i < 2; i++) {
                check(f.service.getSpecializedDetections().timeout(new SpecializedDetectionInput(image("fire-smoke"))) == 180000, "default data deadline");
                check(f.service.getSpecializedDetections().timeout(new SpecializedDetectionInput(control("status", "s"))) == 30000, "default control deadline");
                JSONObject provider = f.service.getSpecializedDetections().snapshot().getJSONArray("providers").getJSONObject(0);
                check(provider.getInt("maxSessions") == 2 && provider.getInt("availableImages") == 1, "default provider budgets");
                check(!provider.has("profiles"), "snapshot does not declare profile support");
                check((Integer) field(f.service.executor, "detectionDataThreads") == 8
                        && (Integer) field(f.service.executor, "detectionDataQueue") == 64
                        && (Integer) field(f.service.executor, "detectionControlThreads") == 2
                        && (Integer) field(f.service.executor, "detectionControlQueue") == 64, "default executor budgets");
                f.service.getSpecializedDetections().configure(new Properties());
                f.service.executor.configureDetection(new Properties());
            }
        }
    }

    /**
     * 校验图像检测：请求封装、结果结构、文件归属授权与错误码映射。
     *
     * @throws Exception 用例执行失败时抛出
     */
    static void images() throws Exception {
        try (Fixture f = new Fixture(2)) {
            for (String profile : Arrays.asList("fire-smoke", "uav-fire-smoke")) {
                Reply reply = f.invoke(image(profile)); reply.success();
                JSONObject wire = f.cellet.requests.get(f.cellet.requests.size() - 1).data;
                check(wire.keySet().equals(new HashSet<>(Arrays.asList("unit", "files", "option"))), "exact unit envelope");
                check(wire.getJSONArray("files").getJSONObject(0).getString("fileCode").equals(FILE), "owned label sent");
                JSONObject result = reply.output.toJSON();
                check(result.getString("response").isEmpty() && result.getString("thought").isEmpty(), "empty text succeeds");
                check(result.getJSONObject("resultPayload").getJSONArray("refs").length() == 0, "zero detections succeed");
                check(result.getJSONObject("performance").getLong("inputTokens") == 0 && result.has("sn") && result.has("timestamp"), "zero usage and association");
            }
            check(f.cellet.contexts.get(0) != f.cellet.contexts.get(1), "equal-load images rotate providers");
            try (java.sql.Connection c = f.service.storage.accounting.ledger.connections.open();
                 java.sql.Statement sql = c.createStatement();
                 java.sql.ResultSet rows = sql.executeQuery("SELECT COUNT(*) AS n FROM aigc_ai_usage_call WHERE quality='actual' AND input_tokens=0 AND output_tokens=0 AND contact_id=1")) {
                check(rows.next() && rows.getInt("n") == 2, "ledger preserves actual zero tokens and visitor");
            }
            check(!f.service.getChannel("channel").isProcessing(), "detection does not occupy chat");
            int sent = f.cellet.requests.size();
            f.service.file = label("domain", 2L); f.invoke(image("fire-smoke")).error(25, 403);
            f.service.file = label("other", 1L); f.invoke(image("fire-smoke")).error(25, 403);
            f.service.file = null; f.invoke(image("fire-smoke")).error(3, 404);
            check(f.cellet.requests.size() == sent, "file authorization precedes transport");
            f.service.file = label("domain", 1L);
            for (int code : new int[]{1,3,7,30,31,32,777}) {
                f.cellet.code = code;
                f.invoke(image("fire-smoke")).error(code, code == 1 ? 400 : code == 3 ? 404 : code == 7 ? 429 : code == 30 ? 503 : code == 32 ? 422 : 502);
            }
            f.cellet.code = 0; f.cellet.malformed = true;
            f.invoke(image("fire-smoke")).error(31, 502);
            f.cellet.malformed = false; f.invoke(image("fire-smoke")).success();
            until(() -> available(f) == 4, "errors release image/start occupancy");
        }
    }

    /**
     * 校验视频流：启动、状态与停止控制、生命周期事件以及轮询补偿。
     *
     * @throws Exception 用例执行失败时抛出
     */
    static void streams() throws Exception {
        try (Fixture f = new Fixture(2)) {
            EventCenter center = EventCenter.getInstance(); center.start(f.service);
            Reply start = f.invoke(start()); start.success(); String id = start.id();
            TalkContext owner = f.cellet.contexts.get(0);
            f.invoke(control("status", id)).success();
            check(f.cellet.contexts.get(1) == owner, "immediate control pinned before callback");
            f.cellet.status = "stopping"; f.invoke(control("stop", id)).success();
            check(available(f) == 3, "stopping consumes session budget");
            center.notifyEvent(new Context(), event("SpecializedDetection", id));
            check(available(f) == 3, "unregistered sender ignored");
            center.notifyEvent(owner, event("OmniGround", id));
            check(available(f) == 3, "other source cannot release specialized session");
            center.notifyEvent(owner, event("SpecializedDetection", id));
            check(available(f) == 4, "trusted stop releases session");
            f.cellet.status = "stopped";
            f.invoke(control("status", id)).success(); f.invoke(control("stop", id)).success();
            expect(25, () -> f.service.executeSpecializedDetection("other", "other-channel", new SpecializedDetectionInput(control("status", id)), new Reply()));
            expect(25, () -> f.service.executeSpecializedDetection("other", "channel", new SpecializedDetectionInput(start()), new Reply()));
            f.cellet.early = true; f.cellet.status = "running";
            Reply early = f.invoke(start()); early.success(); check(available(f) == 4, "stop before start response remains terminal");
            f.cellet.early = false;
            Reply live = f.invoke(start()); live.success();
            f.cellet.status = "stopped";
            forcePoll(f, live.id()); f.service.getSpecializedDetections().reconcile();
            until(() -> available(f) == 4, "poll compensates lost lifecycle event");
            AIGCUnit old = f.service.units.get(0);
            f.service.units.set(0, unit(old.getContact().getId()));
            expect(3, () -> f.service.executeSpecializedDetection("token", "channel", new SpecializedDetectionInput(control("status", id)), new Reply()));
            center.stop();
            try (java.sql.Connection c = f.service.storage.accounting.ledger.connections.open();
                 java.sql.Statement sql = c.createStatement();
                 java.sql.ResultSet rows = sql.executeQuery("SELECT COUNT(*) AS n FROM aigc_ai_usage_call")) {
                check(rows.next() && rows.getInt("n") == 0, "video requests never claim frame token consumption");
            }
        }
    }

    /**
     * 校验准入预留、排队超时以及控制通道与数据通道的隔离。
     *
     * @throws Exception 用例执行失败时抛出
     */
    static void reservationAndQueues() throws Exception {
        try (Fixture f = new Fixture(2)) {
            List<Reply> replies = new ArrayList<>();
            f.cellet.entered = new CountDownLatch(2); f.cellet.release = new CountDownLatch(1);
            for (int i = 0; i < 4; i++) replies.add(f.invoke(start()));
            check(f.cellet.entered.await(3, TimeUnit.SECONDS), "parallel starts entered");
            expect(7, () -> f.invoke(start()));
            check(available(f) == 0, "pending starts atomically reserve capacity");
            f.cellet.release.countDown(); for (Reply reply : replies) reply.success();
            check(available(f) == 0, "active sessions retain capacity");
        }
        try (Fixture f = new Fixture(4)) {
            Properties p = new Properties(); p.setProperty("specialized.detection.data.threads", "1");
            p.setProperty("specialized.detection.data.queue", "1");
            p.setProperty("specialized.detection.data.timeout.ms", "180");
            f.service.executor.configureDetection(p); f.service.getSpecializedDetections().configure(p);
            f.cellet.entered = new CountDownLatch(1); f.cellet.release = new CountDownLatch(1);
            Reply first = f.invoke(image("fire-smoke")); check(f.cellet.entered.await(3, TimeUnit.SECONDS), "first image blocks");
            Reply queued = f.invoke(image("fire-smoke")); Reply full = f.invoke(image("fire-smoke")); full.error(7, 429);
            queued.error(31, 504); first.error(31, 504);
            check(f.cellet.requests.size() == 1, "queued deadline does not send expired image");
            // 唯一的数据线程被占用时，控制类请求仍有独立线程池可用。
            check(f.service.submitDetectionControl(() -> f.cellet.controlWorked.countDown()), "control accepted");
            check(f.cellet.controlWorked.await(2, TimeUnit.SECONDS), "control runs during data saturation");
            f.cellet.release.countDown();
            until(() -> ((JSONArray) f.service.getSpecializedDetections().snapshot().get("providers")).getJSONObject(0).getInt("availableImages") == 1, "late image releases reservation");
        }
    }

    /**
     * 校验超时、迟到响应与连接代际替换的处理。
     *
     * @throws Exception 用例执行失败时抛出
     */
    static void timeoutAndLateReply() throws Exception {
        try (Fixture f = new Fixture(1)) {
            Properties p = new Properties(); p.setProperty("specialized.detection.data.timeout.ms", "100");
            f.service.getSpecializedDetections().configure(p); f.cellet.noReply = true;
            Reply timed = f.invoke(start()); timed.error(31, 504);
            check(available(f) == 1, "uncertain start retains capacity");
            Packet request = f.cellet.requests.get(0); TalkContext owner = f.cellet.contexts.get(0);
            Packet response = f.cellet.response(request, "late", "running");
            f.service.getSpecializedDetections().onResponse(new Context(), response.toDialect());
            check(available(f) == 1, "wrong connection late response ignored");
            f.cellet.noReply = false; f.cellet.status = "stopped";
            f.service.getSpecializedDetections().onResponse(owner, response.toDialect());
            until(() -> sessionCount(f) == 1, "late success registered");
            forcePoll(f, "late"); f.service.getSpecializedDetections().reconcile();
            until(() -> available(f) == 2, "orphan late start automatically stopped");
            check(f.cellet.requests.get(1).data.getJSONObject("option").getString("operation").equals("stop"), "orphan cleanup is stop");
            check(timed.calls.get() == 1, "late success never replies twice");
            // 端点被替换后旧代际即失效，即使联系人标识相同。
            f.cellet.noReply = true; Reply uncertain = f.invoke(start()); uncertain.error(31, 504);
            Packet obsolete = f.cellet.requests.get(f.cellet.requests.size()-1);
            f.service.units.set(0, unit(100L));
            f.service.getSpecializedDetections().onResponse(owner, f.cellet.response(obsolete, "obsolete", "running").toDialect());
            check(available(f) == 2 && sessionCount(f) == 0, "old connection cannot repopulate new generation");
        }
    }

    /**
     * 校验终止会话的保留上限，超出后按最旧优先裁剪。
     *
     * @throws Exception 用例执行失败时抛出
     */
    static void retention() throws Exception {
        try (Fixture f = new Fixture(1)) {
            String first = null, last = null;
            for (int i = 0; i < 22; i++) {
                f.cellet.status = "stopped"; Reply reply = f.invoke(start()); reply.success();
                if (first == null) {
                    first = reply.id();
                }
                last = reply.id();
            }
            check(sessionCount(f) == 20, "20 terminal sessions per provider");
            final String oldest = first;
            expect(3, () -> f.invoke(control("status", oldest)));
            f.invoke(control("stop", last)).success();
        }
    }

    /**
     * 校验配置项与各类失败路径下的容量释放行为。
     *
     * @throws Exception 用例执行失败时抛出
     */
    static void configurationAndFailures() throws Exception {
        try (Fixture f = new Fixture(1)) {
            Properties p = new Properties();
            p.setProperty("specialized.detection.node.domain.100.max.sessions", "1");
            // 已废弃的能力声明不得限制发往 Python 提供方的请求。
            p.setProperty("specialized.detection.profiles", "uav-fire-smoke");
            p.setProperty("specialized.detection.node.domain.100.profiles", "uav-fire-smoke");
            f.service.getSpecializedDetections().configure(p);
            f.invoke(image("fire-smoke")).success();
            for (String profile : Arrays.asList("uav-fire-smoke", "future-profile")) {
                f.cellet.code = 1;
                JSONObject unsupported = start(); unsupported.getJSONObject("option").put("profile", profile);
                f.invoke(unsupported).error(1, 400);
                check(f.cellet.requests.get(f.cellet.requests.size() - 1).data.getJSONObject("option").getString("profile").equals(profile), "unsupported profile reaches Python unchanged");
                check(available(f) == 1, "unsupported profile releases start capacity");
            }
            f.cellet.code = 0;
            JSONObject uav = start(); uav.getJSONObject("option").put("profile", "uav-fire-smoke");
            Reply s = f.invoke(uav); s.success(); expect(7, () -> f.invoke(uav));
            f.cellet.code = 3; f.invoke(control("status", s.id())).error(3, 404);
            check(available(f) == 1, "definitive missing session releases capacity");
            for (int code : new int[]{1,3,7,30,32}) {
                f.cellet.code = code; f.invoke(uav).error(code, SpecializedDetectionException.unit(code).httpStatus);
                check(available(f) == 1, "definitive start rejection releases reservation");
            }
            f.cellet.code = 0; f.cellet.crash = true;
            f.invoke(uav).error(31, 502); check(available(f) == 0, "ambiguous sent failure retains reservation");
            until(() -> f.service.executor.getUnitTaskCountMap().get("SpecializedDetection").get() == 0, "exception releases request count");
        }
        try (Fixture f = new Fixture(1)) {
            Reply start = f.invoke(start()); start.success();
            Properties p = new Properties(); p.setProperty("specialized.detection.control.timeout.ms", "60");
            f.service.getSpecializedDetections().configure(p); f.cellet.noReply = true;
            f.invoke(control("stop", start.id())).error(31, 504);
            check(((Map<?,?>)field(f.service.getSpecializedDetections(),"pending")).isEmpty(), "control timeout cannot leak pending metadata");
            check(available(f) == 1, "uncertain stop retains original session");
        }
        try (Fixture f = new Fixture(1)) {
            f.cellet.entered = new CountDownLatch(1); f.cellet.release = new CountDownLatch(1);
            Reply image = f.invoke(image("fire-smoke")); check(f.cellet.entered.await(3, TimeUnit.SECONDS), "image in flight");
            expect(7, () -> f.invoke(image("uav-fire-smoke")));
            f.cellet.release.countDown(); image.success();
            until(() -> f.service.executor.getUnitTaskCountMap().get("SpecializedDetection").get() == 0, "request count paired");
        }
    }

    /**
     * 统计所有提供方的可用会话数。
     *
     * @param f 测试夹具
     * @return 可用会话总数
     */
    static int available(Fixture f) { int n=0; JSONArray nodes=f.service.getSpecializedDetections().snapshot().getJSONArray("providers"); for(int i=0;i<nodes.length();i++)n+=nodes.getJSONObject(i).getInt("availableSessions");return n; }

    /**
     * 统计会话表中的会话数。
     *
     * @param f 测试夹具
     * @return 会话数
     */
    static int sessionCount(Fixture f) { try{return ((Map<?,?>)field(f.service.getSpecializedDetections(),"sessions")).size();}catch(Exception e){throw new AssertionError(e);} }

    /**
     * 把会话的最近轮询时间置零，使下一轮协调立即发起状态查询。
     *
     * @param f  测试夹具
     * @param id 会话标识
     * @throws Exception 反射访问失败时抛出
     */
    static void forcePoll(Fixture f,String id) throws Exception { Object s=((Map<?,?>)field(f.service.getSpecializedDetections(),"sessions")).get(id); Field p=s.getClass().getDeclaredField("lastPoll");p.setAccessible(true);p.setLong(s,0); }

    /**
     * 构造图像检测输入。
     *
     * @param profile 检测配置名称
     * @return 输入 JSON
     */
    static JSONObject image(String profile) {return new JSONObject().put("files",new JSONArray().put(FILE)).put("option",new JSONObject().put("profile",profile));}

    /**
     * 构造视频流启动输入。
     *
     * @return 输入 JSON
     */
    static JSONObject start() {return new JSONObject().put("files",new JSONArray().put("rtsp://127.0.0.1/test")).put("option",new JSONObject().put("operation","start").put("profile","fire-smoke").put("callbackUrl","http://127.0.0.1/callback"));}

    /**
     * 构造控制类输入。
     *
     * @param op 操作，取值为 status 或 stop
     * @param id 流标识
     * @return 输入 JSON
     */
    static JSONObject control(String op,String id) {return new JSONObject().put("files",new JSONArray()).put("option",new JSONObject().put("operation",op).put("streamId",id));}

    /**
     * 构造流停止的生命周期事件动作。
     *
     * @param source 事件来源
     * @param id     流标识
     * @return 动作对象
     */
    static ActionDialect event(String source,String id) {return new Packet("event",new JSONObject().put("name","streamStopped").put("source",source).put("payload",new JSONObject().put("unit",source).put("streamId",id))).toDialect();}

    /**
     * 构造测试用文件标签。
     *
     * @param domain 域名称
     * @param owner  归属者标识
     * @return 文件标签
     */
    static FileLabel label(String domain,long owner){
        FileLabel label = new FileLabel(domain,FILE,owner,"image.png",12,0,0,Long.MAX_VALUE);
        label.setFileURLs("http://127.0.0.1/test/image.png", "https://127.0.0.1/test/image.png");
        return label;
    }

    /**
     * 构造检测单元。
     *
     * @param id 联系人标识
     * @return 单元实例
     */
    static AIGCUnit unit(long id){return new AIGCUnit(new Contact(id,"domain"),new AICapability("SpecializedDetection","Detection","1.0",Collections.singletonList("Detection"),"test"),new Context());}

    /**
     * 反射读取字段，支持向父类追溯。
     *
     * @param target 目标对象
     * @param name   字段名
     * @return 字段值
     * @throws Exception 字段不存在或访问失败时抛出
     */
    static Object field(Object target,String name)throws Exception {Class<?> c=target.getClass();while(c!=null){try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f.get(target);}catch(NoSuchFieldException e){c=c.getSuperclass();}}throw new NoSuchFieldException(name);}

    /**
     * 断言调用抛出指定状态码的检测异常。
     *
     * @param code 期望的状态码
     * @param job  被测调用
     */
    static void expect(int code,Runnable job){try{job.run();throw new AssertionError("Expected code "+code);}catch(SpecializedDetectionException e){check(e.code==code,"error code "+code);}}

    /**
     * 断言为真并累加断言计数。
     *
     * @param test 断言条件
     * @param name 断言描述
     */
    static void check(boolean test,String name){
        if(!test){
            throw new AssertionError(name);
        }
        assertions++;
    }

    /**
     * 在超时前轮询等待条件成立。
     *
     * @param condition 等待条件
     * @param name      断言描述
     * @throws Exception 等待过程中被中断时抛出
     */
    static void until(BooleanSupplier condition,String name)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(10);check(condition.getAsBoolean(),name);}

    /**
     * 模拟的通信上下文，始终视为有效。
     */
    static final class Context extends TalkContext {Context(){super(null,null);}@Override public boolean isValid(){return true;}}

    /**
     * 记录回调结果的监听器实现。
     */
    static final class Reply implements SpecializedDetectionListener {
        /** 回调完成信号。 */
        final CountDownLatch done=new CountDownLatch(1);
        /** 回调次数。 */
        final AtomicInteger calls=new AtomicInteger();
        /** 成功结果。 */
        volatile SpecializedDetectionOutput output;
        /** 失败原因。 */
        volatile SpecializedDetectionException failure;

        public void onResponse(AIGCChannel c,SpecializedDetectionOutput o){output=o;calls.incrementAndGet();done.countDown();}
        public void onFailed(AIGCChannel c,SpecializedDetectionException e){failure=e;calls.incrementAndGet();done.countDown();}

        /**
         * 等待回调完成，并断言只回调一次。
         *
         * @throws Exception 等待超时或断言失败时抛出
         */
        void await()throws Exception{check(done.await(4,TimeUnit.SECONDS),"request completed");check(calls.get()==1,"one callback");}

        /**
         * 断言本次调用成功。
         *
         * @throws Exception 等待超时或断言失败时抛出
         */
        void success()throws Exception{await();check(failure==null&&output!=null,"successful reply: "+failure);}

        /**
         * 断言本次调用以指定状态码失败。
         *
         * @param code 期望的单元状态码
         * @param http 期望的 HTTP 状态码
         * @throws Exception 等待超时或断言失败时抛出
         */
        void error(int code,int http)throws Exception{await();check(failure!=null&&failure.code==code&&failure.httpStatus==http,"structured failure "+code+"/"+http);}

        /**
         * 取结果中的流标识。
         *
         * @return 流标识
         */
        String id(){return output.resultPayload.getString("streamId");}
    }

    /**
     * 测试夹具，装配假服务、假 Cellet 与提供方单元。
     */
    static final class Fixture implements AutoCloseable {
        /** 假 Cellet。 */
        final FakeCellet cellet=new FakeCellet();
        /** 假服务。 */
        final FakeService service;

        /**
         * 构造夹具并按数量创建提供方单元。
         *
         * @param providers 提供方数量
         * @throws Exception 初始化假服务失败时抛出
         */
        Fixture(int providers)throws Exception{service=new FakeService(cellet);cellet.service=service;for(int i=0;i<providers;i++)service.units.add(unit(100L+i));}

        /**
         * 以默认令牌与通道发起一次检测调用。
         *
         * @param json 检测输入 JSON
         * @return 结果记录对象
         */
        Reply invoke(JSONObject json){Reply r=new Reply();service.executeSpecializedDetection("token","channel",new SpecializedDetectionInput(json),r);return r;}

        public void close(){
            if(cellet.release!=null){
                cellet.release.countDown();
            }
            service.getSpecializedDetections().close();service.executor.shutdown();service.storage.close();EventCenter.getInstance().stop();
        }
    }
    /**
     * 假 AIGC 服务，仅提供检测链路所需的最小能力。
     */
    static final class FakeService extends AIGCService {
        /** 提供方单元列表。 */
        final List<AIGCUnit> units=new CopyOnWriteArrayList<>();
        /** 真实的任务执行器，用于断言线程与队列预算。 */
        final AIGCTaskExecutor executor;
        /** 假存储。 */
        final ChatCompletionServiceTest.FakeStorage storage=new ChatCompletionServiceTest.FakeStorage();
        /** 令牌。 */
        final AuthToken token=new AuthToken("token","domain","app",1L,new Date(),new Date(Long.MAX_VALUE),null,false);
        /** 当前可查到的文件标签。 */
        volatile FileLabel file=label("domain",1L);

        /**
         * 构造方法。
         *
         * @param c 所属 Cellet
         * @throws Exception 反射读取任务执行器失败时抛出
         */
        FakeService(AIGCCellet c)throws Exception{super(c);started.set(true);executor=(AIGCTaskExecutor)field(this,"taskExecutor");}
        @Override public AuthToken getToken(String code){return "token".equals(code)?token:new AuthToken("other","domain","app",2L,new Date(),new Date(Long.MAX_VALUE),null,false);}
        @Override public List<AIGCUnit> getAllUnits(){return units;}
        @Override public FileLabel getFile(String domain,String code){return file;}
        @Override public AIGCStorage getStorage(){return storage;}
    }

    /**
     * 假 Cellet，按预设脚本模拟 Python 单元的响应。
     */
    static final class FakeCellet extends AIGCCellet {
        /** 收到的请求，按顺序保存。 */
        final List<Packet> requests=new CopyOnWriteArrayList<>();
        /** 每次请求使用的通信上下文。 */
        final List<TalkContext> contexts=new CopyOnWriteArrayList<>();
        /** 控制通道执行信号。 */
        final CountDownLatch controlWorked=new CountDownLatch(1);
        /** 流标识自增序号。 */
        final AtomicInteger ids=new AtomicInteger();
        /** 下一次响应携带的状态码。 */
        volatile int code;
        /** 是否返回结构异常的结果。 */
        volatile boolean malformed;
        /** 是否不回复。 */
        volatile boolean noReply;
        /** 是否在回复前先发停止事件。 */
        volatile boolean early;
        /** 是否模拟传输异常。 */
        volatile boolean crash;
        /** 会话状态。 */
        volatile String status="running";
        /** 请求进入信号。 */
        volatile CountDownLatch entered;
        /** 请求放行信号。 */
        volatile CountDownLatch release;
        /** 关联的假服务。 */
        FakeService service;

        @Override public ActionDialect transmit(TalkContext c,ActionDialect d,long timeout,long sn){
            Packet p=new Packet(d);requests.add(p);contexts.add(c);
            if(entered!=null){
                entered.countDown();
            }
            if(crash){
                throw new RuntimeException("simulated transport failure");
            }
            if(release!=null){
                try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            }
            if(noReply){
                return null;
            }
            String id=p.data.getJSONObject("option").optString("streamId","s-"+ids.incrementAndGet());
            if(early){
                service.getSpecializedDetections().event(c,new Event(event("SpecializedDetection",id).getParamAsJson("data")));
            }
            return response(p,id,status).toDialect();
        }

        /**
         * 构造一次单元响应，结果结构按操作类型区分图像与视频。
         *
         * @param p     请求包
         * @param id    流标识
         * @param state 会话状态
         * @return 响应包
         */
        Packet response(Packet p,String id,String state){
            JSONObject option=p.data.getJSONObject("option");
            JSONObject body="detect".equals(option.optString("operation","detect"))?new JSONObject().put("profile",option.getString("profile")).put("width",16).put("height",16).put("refs",new JSONArray()):new JSONObject().put("streamId",id).put("status",state);
            JSONObject output=new JSONObject().put("response","").put("thought","").put("resultPayload",body).put("performance",new JSONObject().put("inputTokens",0).put("outputTokens",0));
            if(malformed){
                output.remove("resultPayload");
            }
            return new Packet(p.sn,p.name,new JSONObject().put("code",code).put("data",output));
        }
    }
}
