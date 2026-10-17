package cube.service.harness.test;

import cube.service.harness.protocol.*;
import cube.service.harness.*;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class ContractTest {
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    static String hex(byte[] bytes){StringBuilder b=new StringBuilder();for(byte v:bytes)b.append(String.format("%02x",v&255));return b.toString();}
    public static void main(String[] args) throws Exception {
        JSONObject manifest=StrictJson.object(Contract.resource("manifest.json"));
        for(String name:manifest.keySet())check(hex(MessageDigest.getInstance("SHA-256").digest(Contract.resource(name))).equals(manifest.getString(name)),"digest: "+name);
        JSONArray fixtures=(JSONArray)StrictJson.parse(Contract.resource("fixtures.json"));
        for(int i=0;i<fixtures.length();i++){
            JSONObject f=fixtures.getJSONObject(i);String outcome="OK";
            try{Contract.parse(f.getString("schema"),f.getString("raw").getBytes(StandardCharsets.UTF_8));}catch(IllegalArgumentException e){outcome=e.getMessage();}
            check(outcome.equals(f.getString("expected")),f.getString("name")+": "+outcome);
        }
        WorkerRegistry registry=new WorkerRegistry(1000);
        JSONObject request=new JSONObject(fixtures.getJSONObject(0).getString("raw"));
        String nonce=registry.apply(request,1).getString("sessionId");
        try{registry.apply(new JSONObject(request.toString()).put("instanceId","new"),2);throw new AssertionError("duplicate accepted");}catch(IllegalArgumentException e){check(e.getMessage().equals("DUPLICATE_WORKER"),"duplicate code");}
        registry.disconnect(1);
        String next=registry.apply(new JSONObject(request.toString()).put("instanceId","new"),2).getString("sessionId");
        check(!nonce.equals(next),"nonce was reused");
        try{registry.apply(request.put("action","heartbeat").put("sessionId",nonce),1);throw new AssertionError("stale accepted");}catch(IllegalArgumentException e){check(e.getMessage().equals("STALE_SESSION"),"stale code");}
        Thread.sleep(1050);check(registry.snapshot().length()==0,"lease not expired");
        System.out.println("PASS: "+fixtures.length()+" fixtures, all digests, duplicate, stale session and lease expiry");
    }
}
