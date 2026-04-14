@echo off
setlocal

echo [1/4] Compiling project...
call compile.bat
if errorlevel 1 (
  echo Compile failed.
  exit /b 1
)

echo.
echo [2/4] Running ORQLCI Energy-Aware benchmark...
call one.bat -b 1 Bench_CCRouting_EnergyAware.txt
if errorlevel 1 (
  echo ORQLCI run failed.
  exit /b 1
)

echo.
echo [3/4] Running PROPHET Energy-Aware benchmark...
call one.bat -b 1 Bench_Prophet_EnergyAware.txt
if errorlevel 1 (
  echo PROPHET run failed.
  exit /b 1
)

echo.
echo [4/4] Running Epidemic Energy-Aware benchmark...
call one.bat -b 1 Bench_Epidemic_EnergyAware.txt
if errorlevel 1 (
  echo Epidemic run failed.
  exit /b 1
)

echo.
echo All benchmark runs completed. Check reports/ directory.
endlocal
