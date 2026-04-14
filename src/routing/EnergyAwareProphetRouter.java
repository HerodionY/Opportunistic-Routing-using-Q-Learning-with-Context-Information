package routing;

import core.ModuleCommunicationBus;
import core.ModuleCommunicationListener;
import core.NetworkInterface;
import core.Settings;
import core.SettingsError;
import core.SimClock;
import core.SimScenario;
import java.util.Random;

/**
 * PRoPHET router with linear, non-restorable battery model.
 */
public class EnergyAwareProphetRouter extends ProphetRouter implements ModuleCommunicationListener {

    public static final String INIT_ENERGY_S = "initialEnergy";
    public static final String LEGACY_INIT_ENERGY_S = "intialEnergy";
    public static final String SCAN_ENERGY_S = "scanEnergy";
    public static final String TRANSMIT_ENERGY_S = "transmitEnergy";
    public static final String WARMUP_S = "energyWarmup";

    public static final String ENERGY_VALUE_ID = CCRouting.ENERGY_VALUE_ID;
    public static final String ENERGY_CAPACITY_ID = CCRouting.ENERGY_CAPACITY_ID;

    private final double[] initEnergy;
    private double initialEnergyCapacity;
    private double warmupTime;
    private double currentEnergy;
    private double scanEnergy;
    private double transmitEnergy;
    private double lastScanUpdate;
    private double lastUpdate;
    private double scanInterval;
    private ModuleCommunicationBus comBus;
    private boolean radioOffByEnergy;
    private static Random rng = null;

    public EnergyAwareProphetRouter(Settings s) {
        super(s);

        if (s.contains(INIT_ENERGY_S)) {
            this.initEnergy = s.getCsvDoubles(INIT_ENERGY_S);
        } else if (s.contains(LEGACY_INIT_ENERGY_S)) {
            this.initEnergy = s.getCsvDoubles(LEGACY_INIT_ENERGY_S);
        } else {
            this.initEnergy = new double[] {1000.0};
        }

        if (this.initEnergy.length != 1 && this.initEnergy.length != 2) {
            throw new SettingsError(INIT_ENERGY_S + " setting must have either one or two values");
        }

        setEnergy(this.initEnergy);
        this.scanEnergy = s.contains(SCAN_ENERGY_S) ? s.getDouble(SCAN_ENERGY_S) : 0.1;
        this.transmitEnergy = s.contains(TRANSMIT_ENERGY_S) ? s.getDouble(TRANSMIT_ENERGY_S) : 0.1;
        this.scanInterval = s.contains(SimScenario.SCAN_INTERVAL_S) ? s.getDouble(SimScenario.SCAN_INTERVAL_S) : 1.0;

        if (s.contains(WARMUP_S)) {
            this.warmupTime = s.getInt(WARMUP_S);
            if (this.warmupTime == -1) {
                this.warmupTime = new Settings(report.Report.REPORT_NS).getInt(report.Report.WARMUP_S);
            }
        } else {
            this.warmupTime = 0;
        }

        this.comBus = null;
        this.lastScanUpdate = 0.0;
        this.lastUpdate = 0.0;
        this.radioOffByEnergy = false;
    }

    protected EnergyAwareProphetRouter(EnergyAwareProphetRouter r) {
        super(r);
        this.initEnergy = r.initEnergy;
        setEnergy(this.initEnergy);
        this.scanEnergy = r.scanEnergy;
        this.transmitEnergy = r.transmitEnergy;
        this.scanInterval = r.scanInterval;
        this.warmupTime = r.warmupTime;
        this.comBus = null;
        this.lastScanUpdate = 0.0;
        this.lastUpdate = 0.0;
        this.radioOffByEnergy = false;
    }

    protected void setEnergy(double[] range) {
        if (range.length == 1) {
            this.currentEnergy = range[0];
            this.initialEnergyCapacity = range[0];
        } else {
            if (rng == null) {
                rng = new Random((int) (range[0] + range[1]));
            }
            this.currentEnergy = range[0] + rng.nextDouble() * (range[1] - range[0]);
            this.initialEnergyCapacity = range[1];
        }
    }

    private void initComBusIfNeeded() {
        if (this.comBus == null) {
            this.comBus = getHost().getComBus();
        }
        if (this.comBus.getProperty(ENERGY_VALUE_ID) == null) {
            this.comBus.addProperty(ENERGY_VALUE_ID, this.currentEnergy);
        } else {
            this.comBus.updateProperty(ENERGY_VALUE_ID, this.currentEnergy);
        }
        if (this.comBus.getProperty(ENERGY_CAPACITY_ID) == null) {
            this.comBus.addProperty(ENERGY_CAPACITY_ID, this.initialEnergyCapacity);
        } else {
            this.comBus.updateProperty(ENERGY_CAPACITY_ID, this.initialEnergyCapacity);
        }
        this.comBus.subscribe(ENERGY_VALUE_ID, this);
    }

    protected void reduceEnergy(double amount) {
        if (SimClock.getTime() < this.warmupTime) {
            return;
        }
        initComBusIfNeeded();
        this.currentEnergy -= amount;
        if (this.currentEnergy < 0) {
            this.currentEnergy = 0;
        }
        this.comBus.updateProperty(ENERGY_VALUE_ID, this.currentEnergy);

        if (this.currentEnergy <= 0 && !this.radioOffByEnergy) {
            this.comBus.updateProperty(NetworkInterface.RANGE_ID, 0.0);
            this.radioOffByEnergy = true;
        }
    }

    protected void reduceSendingAndScanningEnergy() {
        double simTime = SimClock.getTime();
        initComBusIfNeeded();

        if (this.currentEnergy <= 0) {
            if (!this.radioOffByEnergy) {
                this.comBus.updateProperty(NetworkInterface.RANGE_ID, 0.0);
                this.radioOffByEnergy = true;
            }
            return;
        }

        if (simTime > this.lastUpdate && this.sendingConnections.size() > 0) {
            reduceEnergy((simTime - this.lastUpdate) * this.transmitEnergy);
        }
        this.lastUpdate = simTime;

        if (simTime > this.lastScanUpdate + this.scanInterval) {
            reduceEnergy(this.scanEnergy);
            this.lastScanUpdate = simTime;
        }
    }

    @Override
    protected boolean canStartTransfer() {
        if (this.currentEnergy <= 0) {
            return false;
        }
        return super.canStartTransfer();
    }

    @Override
    protected int checkReceiving(core.Message m) {
        if (this.currentEnergy <= 0) {
            return DENIED_UNSPECIFIED;
        }
        return super.checkReceiving(m);
    }

    @Override
    public void update() {
        super.update();
        reduceSendingAndScanningEnergy();
    }

    @Override
    public void moduleValueChanged(String key, Object newValue) {
        if (ENERGY_VALUE_ID.equals(key)) {
            this.currentEnergy = (Double) newValue;
        }
    }

    public double getCurrentEnergy() {
        return this.currentEnergy;
    }

    public double getInitialEnergyCapacity() {
        return this.initialEnergyCapacity;
    }

    public double getEnergyFactor() {
        if (this.initialEnergyCapacity <= 0) {
            return 0;
        }
        double ef = this.currentEnergy / this.initialEnergyCapacity;
        if (ef < 0) {
            ef = 0;
        }
        if (ef > 1) {
            ef = 1;
        }
        return ef;
    }

    public boolean isDeadNode() {
        return this.currentEnergy <= 0;
    }

    @Override
    public EnergyAwareProphetRouter replicate() {
        return new EnergyAwareProphetRouter(this);
    }
}
