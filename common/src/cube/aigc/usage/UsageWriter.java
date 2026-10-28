package cube.aigc.usage;

import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;

/** Fsynced terminal evidence survives a database outage and is replayed idempotently. */
public final class UsageWriter implements AutoCloseable {
    public final UsageLedger ledger;
    private final Path directory;
    private final ScheduledExecutorService replay;
    private volatile boolean healthy=true;
    public UsageWriter(UsageLedger ledger,Path directory) {
        this.ledger=ledger;this.directory=directory;
        try {Files.createDirectories(directory);}catch(IOException e){throw new IllegalStateException("AI_USAGE_JOURNAL_UNAVAILABLE",e);}
        replay=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"ai-usage-replay");t.setDaemon(true);return t;});
        replay();replay.scheduleWithFixedDelay(this::replay,10,10,TimeUnit.SECONDS);
    }
    public void begin(UsageContext context,String id,String model,String unit,long now){
        if(context.delegated)return;
        if(!healthy)throw new IllegalStateException("AI_USAGE_TEMPORARILY_UNAVAILABLE");
        try { ledger.begin(context,id,model,unit,now); }
        catch (RuntimeException e) { healthy=false;throw e; }
    }
    /** Admission check for autonomous streams; individual attempts still require begin(). */
    public void checkAvailable() {
        if (!healthy) throw new IllegalStateException("AI_USAGE_TEMPORARILY_UNAVAILABLE");
        try (java.sql.Connection c=ledger.connections.open()) { ledger.metadata(c); }
        catch (Exception e) { healthy=false;throw new IllegalStateException("AI_USAGE_STORAGE_UNAVAILABLE",e); }
    }
    public synchronized void finish(JSONObject evidence) {
        Path target=directory.resolve(UsageLedger.hash(evidence.getString("meterId")+":"+evidence.getLong("revision"))+".json");
        try {
            byte[] bytes=evidence.toString().getBytes(StandardCharsets.UTF_8);
            if(Files.exists(target)){
                JSONObject old=new JSONObject(new String(Files.readAllBytes(target),StandardCharsets.UTF_8));
                if(!UsageLedger.evidenceHash(old).equals(UsageLedger.evidenceHash(evidence)))throw new IOException("USAGE_JOURNAL_CONFLICT");
            }else{
                Path temp=Files.createTempFile(directory,"evidence-",".tmp");
                try(FileOutputStream out=new FileOutputStream(temp.toFile())){out.write(bytes);out.getFD().sync();}
                try{Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(temp,target);}
            }
            try{ledger.settle(evidence);Files.deleteIfExists(target);}catch(RuntimeException db){healthy=false;}
        } catch(IOException e){healthy=false;throw new IllegalStateException("AI_USAGE_JOURNAL_UNAVAILABLE",e);}
    }
    public synchronized void replay(){
        try(DirectoryStream<Path> entries=Files.newDirectoryStream(directory,"*.json")){
            for(Path file:entries){ledger.settle(new JSONObject(new String(Files.readAllBytes(file),StandardCharsets.UTF_8)));Files.delete(file);}
            try(java.sql.Connection c=ledger.connections.open()){ledger.metadata(c);}
            healthy=true;
        }catch(Exception e){healthy=false;}
    }
    public boolean healthy(){return healthy;}
    public void close(){replay.shutdownNow();replay();}
}
