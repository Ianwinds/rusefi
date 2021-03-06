#!/bin/sh

rm gen_live_documentation.log

java -DSystemOut.name=gen_live_documentation \
 -jar ../java_tools/ConfigDefinition.jar \
 -definition integration/pid_state.txt \
 -java_destination ../java_console/models/src/com/rusefi/config/generated/PidState.java \
 -c_destination controllers/generated/pid_state_generated.h

java -DSystemOut.name=gen_live_documentation \
 -jar ../java_tools/ConfigDefinition.jar \
 -definition integration/engine_state.txt \
 -java_destination ../java_console/models/src/com/rusefi/config/generated/EngineState.java \
 -c_destination controllers/generated/engine_state_generated.h

java -DSystemOut.name=gen_live_documentation \
 -jar ../java_tools/ConfigDefinition.jar \
 -definition integration/trigger_central.txt \
 -java_destination ../java_console/models/src/com/rusefi/config/generated/TriggerCentral.java \
 -c_destination controllers/generated/trigger_central_generated.h

java -DSystemOut.name=gen_live_documentation \
 -jar ../java_tools/ConfigDefinition.jar \
 -definition integration/trigger_state.txt \
 -java_destination ../java_console/models/src/com/rusefi/config/generated/TriggerState.java \
 -c_destination controllers/generated/trigger_state_generated.h

java -DSystemOut.name=gen_live_documentation \
 -jar ../java_tools/ConfigDefinition.jar \
 -definition integration/wall_fuel_state.txt \
 -java_destination ../java_console/models/src/com/rusefi/config/generated/WallFuelState.java \
 -c_destination controllers/generated/wall_fuel_generated.h

java -DSystemOut.name=gen_live_documentation \
 -cp ../java_tools/ConfigDefinition.jar \
 com.rusefi.ldmp.UsagesReader integration/LiveData.yaml
