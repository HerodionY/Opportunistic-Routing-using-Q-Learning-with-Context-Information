import subprocess
import os

# --- KONFIGURASI ENV ---
JVM_ARGS = "-Xmx12G"
CLASSPATH = "target;lib/ECLA.jar;lib/DTNConsoleConnection.jar;lib/lombok.jar"
MAIN_CLASS = "core.DTNSim"
BASE_FILE = "Bench_CCRouting.txt" 

# --- DEFINISI 12 VARIASI ---
scenarios = {
    "SP_Buffer_Cripple":   {"Group.bufferSize": "2M", "Group.movementModel": "ShortestPathMapBasedMovement", "Scenario.nrofHosts": "100"},
    "SP_Storm_Load":       {"Events1.interval": "1, 2", "Events1.size": "1M, 2M", "Group.movementModel": "ShortestPathMapBasedMovement"},
    "SP_Hyper_Mobility":   {"Group.speed": "15, 25", "Group.movementModel": "ShortestPathMapBasedMovement"},
    "SP_Short_Range":      {"btInterface.transmitRange": "2", "Group.movementModel": "ShortestPathMapBasedMovement"},
    "SP_Big_Message":      {"Events1.size": "20M, 30M", "Group.bufferSize": "50M"},
    "SP_Mega_Scale":       {"Scenario.nrofHosts": "500", "CCRouting.totalAction": "500"},
    "RT_Sparse_Death":     {"Group.msgTtl": "15", "Events1.class": "ExternalEventsQueue", "Group.movementModel": "StationaryMovement", "Scenario.simulateConnections": "false"},
    "RT_Long_Wait":        {"Events1.interval": "3600, 3600", "Events1.class": "ExternalEventsQueue", "Group.movementModel": "StationaryMovement"},
    "RT_High_TTL_Greedy":  {"Group.msgTtl": "20160", "Events1.class": "ExternalEventsQueue", "Group.movementModel": "StationaryMovement"},
    "RT_Small_Buffer":     {"Group.bufferSize": "5M", "Events1.class": "ExternalEventsQueue", "Group.movementModel": "StationaryMovement"},
    "RT_Target_Specific":  {"Events1.hosts": "0, 5", "Scenario.nrofHosts": "100", "Events1.class": "ExternalEventsQueue"},
    "RT_Max_Endurance":    {"Scenario.endTime": "2592000", "Events1.class": "ExternalEventsQueue", "Group.movementModel": "StationaryMovement"}
}

def run_all():
    if not os.path.exists(BASE_FILE):
        print(f"Error: File {BASE_FILE} tidak ditemukan!")
        return

    for name, overrides in scenarios.items():
        print(f"\n[PREPARING] Skenario: {name}")
        
        # 1. Bikin folder report (Gunakan Forward Slash agar Java tidak pusing)
        report_path_raw = f"reports/STRESS_TEST/{name}"
        if not os.path.exists(report_path_raw):
            os.makedirs(report_path_raw)
            print(f"[MKDIR] Folder siap: {report_path_raw}")

        # 2. Baca template
        with open(BASE_FILE, 'r') as f:
            lines = f.readlines()
        
        new_config_name = f"cfg_{name}.txt"
        with open(new_config_name, 'w') as f:
            for line in lines:
                written = False
                for key, val in overrides.items():
                    if line.strip().startswith(key + " =") or line.strip().startswith(key + "="):
                        f.write(f"{key} = {val}\n")
                        written = True
                        break
                if not written:
                    f.write(line)
        
        # 3. Jalankan Java
        print(f"[RUNNING] {name}...")
        
        # TRIK: Gunakan forward slash dan hilangkan Absolute Path yang terlalu panjang
        # Kita pakai path relatif saja agar Command Line tidak terlalu panjang
        final_report_path = report_path_raw.replace("\\", "/")
        
        # Bungkus seluruh argumen settings dengan tanda kutip
        cmd = f'java {JVM_ARGS} -cp "{CLASSPATH}" {MAIN_CLASS} -b 1 "{new_config_name}" "Report.reportDir={final_report_path}"'
        
        try:
            subprocess.run(cmd, shell=True, check=True)
            print(f"[SUCCESS] {name} selesai.")
        except subprocess.CalledProcessError as e:
            print(f"[FAILED] {name} error: {e}")

if __name__ == "__main__":
    run_all()