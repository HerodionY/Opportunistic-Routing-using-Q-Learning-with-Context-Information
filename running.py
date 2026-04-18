import subprocess
import os
import concurrent.futures
import time

# --- KONFIGURASI ENV ---
JVM_ARGS = "-Xmx4G"  # Dibatasi 4GB agar aman untuk 3 proses sekaligus di RAM 12GB
MAX_WORKERS = 3     # Sesuai permintaan: 3 simulasi jalan berbarengan (Queueing)

# Alamat Absolut agar tidak bingung antar device
BASE_DIR = os.path.abspath(os.getcwd()).replace("\\", "/")
LIB_DIR = f"{BASE_DIR}/lib"
TARGET_DIR = f"{BASE_DIR}/target"

# Update Classpath secara dinamis dengan ABSOLUTE PATH
lib_jars = [f"{LIB_DIR}/{f}" for f in os.listdir("lib") if f.endswith(".jar")]
CLASSPATH = os.pathsep.join([TARGET_DIR] + lib_jars)

MAIN_CLASS = "core.DTNSim"
BASE_FILE = "Bench_CCRouting.txt" 
BASE_REPORT_DIR = f"{BASE_DIR}/reports/STRESS_TEST"

# --- DEFINISI 12 VARIASI ---
# (Isi scenarios tetap sama seperti sebelumnya)
scenarios = {
    "SP_Buffer_Cripple":   {
        "Group.bufferSize": "2M", 
        "Group.movementModel": "ShortestPathMapBasedMovement", 
        "Group1.nrofHosts": "100",
        "CCRouting.totalAction": "100",
        "Events2.hosts": "0, 99"
    },
    "SP_Storm_Load":       {
        "Events2.interval": "1, 2", 
        "Events2.size": "1M, 2M", 
        "Group.movementModel": "ShortestPathMapBasedMovement"
    },
    "SP_Hyper_Mobility":   {
        "Group.speed": "15, 25", 
        "Group.movementModel": "ShortestPathMapBasedMovement"
    },
    "SP_Short_Range":      {
        "btInterface.transmitRange": "2", 
        "Group.movementModel": "ShortestPathMapBasedMovement"
    },
    "SP_Big_Message":      {
        "Events2.size": "20M, 30M", 
        "Group.bufferSize": "50M"
    },
    "SP_Mega_Scale":       {
        "Group1.nrofHosts": "500", 
        "CCRouting.totalAction": "500",
        "Events2.hosts": "0, 499"
    },
    "RT_Sparse_Death":     {
        "Group.msgTtl": "15", 
        "Events1.class": "ExternalEventsQueue", 
        "Group.movementModel": "StationaryMovement", 
        "Scenario.simulateConnections": "false"
    },
    "RT_Long_Wait":        {
        "Events2.interval": "3600, 3600", 
        "Events1.class": "ExternalEventsQueue", 
        "Group.movementModel": "StationaryMovement"
    },
    "RT_High_TTL_Greedy":  {
        "Group.msgTtl": "20160", 
        "Events1.class": "ExternalEventsQueue", 
        "Group.movementModel": "StationaryMovement"
    },
    "RT_Small_Buffer":     {
        "Group.bufferSize": "5M", 
        "Events1.class": "ExternalEventsQueue", 
        "Group.movementModel": "StationaryMovement"
    },
    "RT_Target_Specific":  {
        "Events2.hosts": "0, 5", 
        "Group1.nrofHosts": "100", 
        "CCRouting.totalAction": "100",
        "Events1.class": "ExternalEventsQueue"
    },
    "RT_Max_Endurance":    {
        "Scenario.endTime": "2592000", 
        "Events1.class": "ExternalEventsQueue", 
        "Group.movementModel": "StationaryMovement"
    }
}

def run_scenario(name, overrides):
    """Fungsi untuk menjalankan satu skenario simulasi"""
    print(f"[QUEUED] Skenario: {name}")
    
    # 1. Persiapan Folder Report (Pakai Absolute Path)
    report_path = f"{BASE_REPORT_DIR}/{name}"
    if not os.path.exists(report_path):
        os.makedirs(report_path)
    
    # 2. Tambahkan Report.reportDir ke dalam overrides agar ditulis ke file config
    # Kita buat copy agar tidak merusak data asli
    current_overrides = overrides.copy()
    current_overrides["Report.reportDir"] = f"{report_path}/" # Wajib akhiri dengan /

    # 3. Pembuatan File Config Baru
    new_config_name = f"cfg_{name}.txt"
    try:
        with open(BASE_FILE, 'r') as f:
            lines = f.readlines()
        
        with open(new_config_name, 'w') as f:
            for line in lines:
                written = False
                for key, val in current_overrides.items():
                    # Cek apakah line ini mengandung pengaturan yang mau kita override
                    if line.strip().startswith(key + " =") or line.strip().startswith(key + "="):
                        f.write(f"{key} = {val}\n")
                        written = True
                        break
                if not written:
                    f.write(line)
                    
            # Tambahkan settings baru jika belum ada di file original
            # (Berguna jika kita menambahkan parameter baru ke overrides)
            for key, val in current_overrides.items():
                # Cek secara sederhana apakah sudah ditulis tadi
                already_in_file = any((l.strip().startswith(key + " =") or l.strip().startswith(key + "=")) for l in lines)
                if not already_in_file:
                    f.write(f"{key} = {val}\n")
                    
    except Exception as e:
        return f"[ERROR] Gagal membuat config {name}: {e}"

    # 4. Eksekusi Java (Tanpa argumen Report.reportDir di cmd karena sudah ada di file config)
    log_file = f"{report_path}/sim_output.log"
    cmd = f'java {JVM_ARGS} -cp "{CLASSPATH}" {MAIN_CLASS} -b 1 "{new_config_name}"'
    
    start_time = time.time()
    print(f"[RUNNING] {name} (Log: {log_file})...")
    
    try:
        with open(log_file, "w") as f_log:
            subprocess.run(cmd, shell=True, check=True, stdout=f_log, stderr=f_log)
        
        duration = (time.time() - start_time) / 60
        return f"[SUCCESS] {name} selesai dalam {duration:.2f} menit."
    except subprocess.CalledProcessError as e:
        return f"[FAILED] {name} error (Cek log: {log_file})"
    finally:
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