#ifndef EP_WARNING_SERVICE_H
#define EP_WARNING_SERVICE_H

#include <stdbool.h>
#include "shake_detector.h"

#define APP_ID_SERVICE "com.epwarning.watch.service"
#define APP_ID_UI      "com.epwarning.watch.ui"

#define APP_CONTROL_OP_START "com.epwarning.watch.START"
#define APP_CONTROL_OP_STOP  "com.epwarning.watch.STOP"
#define APP_CONTROL_OP_CONFIG "com.epwarning.watch.CONFIG"

#define PREF_SENSITIVITY      "sensitivity"
#define PREF_SUSTAIN_SECONDS  "sustain_seconds"
#define PREF_MONITORING_ON    "monitoring_on"
#define PREF_LAST_ALARM_TIME  "last_alarm_time"

bool detector_start(void);
void detector_stop(void);
bool detector_is_running(void);
void detector_apply_config(const shake_config_t *cfg);

#endif // EP_WARNING_SERVICE_H
