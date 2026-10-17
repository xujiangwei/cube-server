package cube.service.harness;

import cell.api.Nucleus;
import cell.carpet.CellListener;
import cube.core.Kernel;

/** Dedicated Harness-only container. Cohosting uses the existing ServiceCarpet instead. */
public final class HarnessCarpet implements CellListener {
    private Kernel kernel;
    @Override public void cellPreinitialize(Nucleus nucleus) { kernel=new Kernel(nucleus);nucleus.setParameter("kernel",kernel); }
    @Override public void cellInitialized(Nucleus nucleus) { kernel.startup(); }
    @Override public void cellPredestroy(Nucleus nucleus) { kernel.dispose(); }
    @Override public void cellDestroyed(Nucleus nucleus) { kernel.shutdown(); }
}
