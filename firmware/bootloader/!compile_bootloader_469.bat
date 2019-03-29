@echo off

echo Starting compilation for Prometheus-469

set PROJECT_BOARD=Prometheus
set PROMETHEUS_BOARD=469
set EXTRA_PARAMS=-DDUMMY -DSTM32F469xx -DEFI_ENABLE_ASSERTS=FALSE -DCH_DBG_ENABLE_CHECKS=FALSE -DCH_DBG_ENABLE_TRACE=FALSE -DCH_DBG_ENABLE_ASSERTS=FALSE -DCH_DBG_ENABLE_STACK_CHECK=FALSE -DCH_DBG_FILL_THREADS=FALSE -DCH_DBG_THREADS_PROFILING=FALSE
set BOOTLOADER_CODE_DESTINATION_PATH="../Prometheus/469"
set DEBUG_LEVEL_OPT="-O2"

call !compile_bootloader.bat -r
