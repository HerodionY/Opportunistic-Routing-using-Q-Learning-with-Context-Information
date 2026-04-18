import subprocess
import os
import concurrent.futures
import time

# --- KONFIGURASI ENV ---
JVM_ARGS = "-Xmx4G"  # Dibatasi 4GB agar aman untuk 3 proses sekaligus di RAM 12GB
MAX_WORKERS = 3     # Sesuai permintaan: 3 simulasi jalan berbarengan (Queueing)

# Update Classpath secara dinamis untuk menyertakan SEMUA jar di folder lib
lib_jars = [os.path.join("lib", f) for f in os.listdir("lib") if f.endswith(".jar")]
CLASSPATH = os.pathsep.join(["target"] + lib_jars)

MAIN_CLASS = "core.DTNSim"
BASE_FILE = "Bench_CCRouting.txt" 
BASE_REPORT_DIR = "reports/STRESS_TEST"

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

def run_scenario(name, overrides):
    """Fungsi untuk menjalankan satu skenario simulasi"""
    print(f"[QUEUED] Skenario: {name}")
    
    # 1. Persiapan Folder Report
    report_path = f"{BASE_REPORT_DIR}/{name}"
    if not os.path.exists(report_path):
        os.makedirs(report_path)
    
    # 2. Pembuatan File Config Baru
    new_config_name = f"cfg_{name}.txt"
    try:
        with open(BASE_FILE, 'r') as f:
            lines = f.readlines()
        
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
    except Exception as e:
        return f"[ERROR] Gagal membuat config {name}: {e}"

    # 3. Eksekusi Java
    log_file = f"{report_path}/sim_output.log"
    cmd = f'java {JVM_ARGS} -cp "{CLASSPATH}" {MAIN_CLASS} -b 1 "{new_config_name}" "Report.reportDir={report_path}"'
    
    start_time = time.time()
    print(f"[RUNNING] {name} (Logging to {log_file})...")
    
    try:
        with open(log_file, "w") as f_log:
            subprocess.run(cmd, shell=True, check=True, stdout=f_log, stderr=f_log)
        
        duration = (time.time() - start_time) / 60
        return f"[SUCCESS] {name} selesai dalam {duration:.2f} menit."
    except subprocess.CalledProcessError as e:
        return f"[FAILED] {name} error (Cek log: {log_file})"
    finally:
        # Opsional: Hapus config temporary jika ingin bersih-bersih
        if os.path.exists(new_config_name):
            os.remove(new_config_name)

def run_all():
    if not os.path.exists(BASE_FILE):
        print(f"Error: File {BASE_FILE} tidak ditemukan!")
        return

    if not os.path.exists(BASE_REPORT_DIR):
        os.makedirs(BASE_REPORT_DIR)

    print(f"--- MEMULAI SIMULASI PARALEL (Workers: {MAX_WORKERS}) ---")
    
    # Menggunakan ProcessPoolExecutor untuk Queueing otomatis
    with concurrent.futures.ProcessPoolExecutor(max_workers=MAX_WORKERS) as executor:
        # Submit semua skenario ke antrean
        future_to_name = {
            executor.submit(run_scenario, name, overrides): name 
            for name, overrides in scenarios.items()
        }
        
        # Ambil hasil saat selesai
        for future in concurrent.futures.as_completed(future_to_name):
            result = future.result()
            print(result)

if __name__ == "__main__":
    run_all()