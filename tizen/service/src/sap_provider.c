#include "sap_provider.h"

#include <dlog.h>
#include <sap.h>          // From Samsung Accessory SDK (Tizen Studio package "Samsung Wearable Extension").
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define LOG_TAG "EP_WARNING"

#define SAP_PROFILE_ID "/com/epwarning/alarm"
#define SAP_CHANNEL_ID 104

static struct sap_provider_state {
    sap_agent_h agent;
    sap_peer_agent_h peer;
    sap_socket_h socket;
    bool connected;
} g_state;

int alarm_payload_encode(const alarm_payload_t *p, char *buf, int buf_len) {
    if (!p || !buf || buf_len <= 0) return -1;
    return snprintf(
        buf, (size_t)buf_len,
        "{\"id\":\"%s\",\"triggeredAtEpochMs\":%lld,\"peakIntensity\":%.3f,\"sustainedSeconds\":%.3f}",
        p->id, (long long)p->triggered_at_epoch_ms, p->peak_intensity, p->sustained_seconds);
}

static void on_data_received(sap_socket_h socket, unsigned short channel_id,
                             unsigned int payload_size, void *payload, void *user_data) {
    (void)socket; (void)user_data; (void)channel_id;
    dlog_print(DLOG_INFO, LOG_TAG, "SAP rx: %u bytes", payload_size);
    // Phone-to-watch messages (sensitivity changes etc.) would land here. Not used in v0.
    (void)payload;
}

static void on_service_connection(sap_peer_agent_h peer, sap_socket_h socket,
                                  sap_service_connection_result_e result, void *user_data) {
    (void)user_data;
    if (result != SAP_CONNECTION_SUCCESS) {
        dlog_print(DLOG_ERROR, LOG_TAG, "SAP connection failed: %d", result);
        return;
    }
    g_state.peer = peer;
    g_state.socket = socket;
    g_state.connected = true;
    sap_socket_set_data_received_cb(socket, on_data_received, NULL);
    dlog_print(DLOG_INFO, LOG_TAG, "SAP connected to phone");
}

static void on_peer_request(sap_peer_agent_h peer, sap_peer_agent_status_e status,
                            sap_peer_agent_found_result_e result, void *user_data) {
    (void)user_data; (void)status; (void)result; (void)peer;
    // No-op: we accept incoming consumer requests via on_service_connection.
}

static void on_agent_init(sap_agent_h agent, sap_agent_initialized_result_e result, void *user_data) {
    (void)user_data;
    if (result != SAP_AGENT_INITIALIZED_SUCCESS) {
        dlog_print(DLOG_ERROR, LOG_TAG, "SAP agent init failed: %d", result);
        return;
    }
    g_state.agent = agent;
    sap_agent_set_service_connection_requested_cb(agent, on_service_connection, NULL);
    sap_agent_set_peer_agent_status_changed_cb(agent, on_peer_request, NULL);
    dlog_print(DLOG_INFO, LOG_TAG, "SAP agent initialized");
}

bool sap_provider_start(void) {
    memset(&g_state, 0, sizeof(g_state));
    int rc = sap_agent_initialize(&g_state.agent, SAP_PROFILE_ID, SAP_AGENT_ROLE_PROVIDER,
                                  on_agent_init, NULL);
    if (rc != SAP_RESULT_SUCCESS) {
        dlog_print(DLOG_ERROR, LOG_TAG, "sap_agent_initialize -> %d", rc);
        return false;
    }
    return true;
}

void sap_provider_stop(void) {
    if (g_state.socket) {
        sap_socket_close(g_state.socket);
        g_state.socket = NULL;
    }
    if (g_state.agent) {
        sap_agent_deinitialize(g_state.agent);
        g_state.agent = NULL;
    }
    g_state.connected = false;
}

bool sap_provider_is_connected(void) {
    return g_state.connected && g_state.socket != NULL;
}

bool sap_provider_send_alarm(const alarm_payload_t *payload) {
    if (!sap_provider_is_connected()) {
        dlog_print(DLOG_WARN, LOG_TAG, "SAP send: not connected");
        return false;
    }
    char buf[256];
    int len = alarm_payload_encode(payload, buf, sizeof(buf));
    if (len <= 0) return false;
    int rc = sap_socket_send_data(g_state.socket, SAP_CHANNEL_ID,
                                  (unsigned int)len, (void *)buf);
    if (rc != SAP_RESULT_SUCCESS) {
        dlog_print(DLOG_ERROR, LOG_TAG, "sap_socket_send_data -> %d", rc);
        return false;
    }
    return true;
}
