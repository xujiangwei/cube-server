package cube.service.harness;

import cell.carpet.Cell;
import java.nio.file.*;

/** XML bootstrap and an instance-specific stop marker, suitable for foreground supervisors. */
public final class HarnessCell {
    public static void main(String[] args) throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("HarnessCell <cell.xml> <private-stop-file>");
        // This Cell version's start(boolean,String) takes a LOG filename. Its
        // programmatic entry always reads cell.xml from the working directory.
        if(!Files.isSameFile(Paths.get(args[0]),Paths.get("cell.xml")))
            throw new IllegalArgumentException("Select config as cell.xml in a dedicated working directory");
        Cell cell=new Cell();
        if(!cell.start(false,"harness.log"))throw new IllegalStateException("Cell startup failed");
        Runtime.getRuntime().addShutdownHook(new Thread(cell::stop,"harness-cell-shutdown"));
        Path stop=Paths.get(args[1]);
        while(!Files.exists(stop))Thread.sleep(200);
        cell.stop();
    }
}
