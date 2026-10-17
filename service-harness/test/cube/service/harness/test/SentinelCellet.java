package cube.service.harness.test;

import cell.core.cellet.Cellet;
import cell.core.talk.*;
import cube.core.Kernel;

/** Test-only cohost; omitted from the production Cellet jar. */
public final class SentinelCellet extends Cellet {
    public SentinelCellet(){super("Sentinel");}
    public boolean install(){return true;}
    public void uninstall(){}
    public void onListened(TalkContext context,Primitive primitive){
        if(primitive.getStuff(0).getValueAsString().equals("stopHarness"))
            ((Kernel)getNucleus().getParameter("kernel")).getModule("Harness").stop();
        Primitive reply=new Primitive();reply.commit(new Stuff("sentinel-alive"));speak(context,reply);
    }
}
