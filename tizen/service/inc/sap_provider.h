#ifndef EP_WARNING_SAP_PROVIDER_H
#define EP_WARNING_SAP_PROVIDER_H

#include <stdbool.h>
#include <stdint.h>

typedef struct {
    char id[64];
    int64_t triggered_at_epoch_ms;
    float peak_intensity;
    float sustained_seconds;
} alarm_payload_t;

// Encode payload as JSON. Buffer must be at least 256 bytes. Returns bytes written or -1.
int alarm_payload_encode(const alarm_payload_t *p, char *buf, int buf_len);

// Initialize SAP, register the service profile, start listening for the consumer (phone) to connect.
// Safe to call once at service start.
bool sap_provider_start(void);

// Tear down SAP cleanly. Safe to call at service termination.
void sap_provider_stop(void);

// True if the consumer (phone) has an open socket.
bool sap_provider_is_connected(void);

// Send an alarm. Returns true if the SAP layer accepted the send.
bool sap_provider_send_alarm(const alarm_payload_t *payload);

#endif // EP_WARNING_SAP_PROVIDER_H
