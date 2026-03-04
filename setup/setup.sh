#!/bin/bash
set -e

MATRIX_SERVER="${MATRIX_SERVER_URL:-http://matrix:8008}"
MATRIX_SERVER_NAME="${MATRIX_SERVER_NAME:-matrix.local}"

echo "Waiting for Matrix server to be ready..."
until curl -sf "${MATRIX_SERVER}/health" > /dev/null 2>&1; do
    echo "Matrix not ready yet, waiting..."
    sleep 2
done
echo "Matrix server is ready!"

# Register HIS user
echo "Registering HIS user..."
HIS_RESPONSE=$(curl -sf -X POST "${MATRIX_SERVER}/_matrix/client/v3/register" \
    -H "Content-Type: application/json" \
    -d '{
        "username": "his_user",
        "password": "his_password",
        "auth": {
            "type": "m.login.dummy"
        }
    }' 2>/dev/null || true)

if echo "$HIS_RESPONSE" | grep -q "access_token"; then
    HIS_TOKEN=$(echo "$HIS_RESPONSE" | jq -r '.access_token')
    echo "HIS user registered successfully"
elif echo "$HIS_RESPONSE" | grep -q "M_USER_IN_USE"; then
    echo "HIS user already exists, logging in..."
    HIS_TOKEN=$(curl -sf -X POST "${MATRIX_SERVER}/_matrix/client/v3/login" \
        -H "Content-Type: application/json" \
        -d '{
            "type": "m.login.password",
            "user": "his_user",
            "password": "his_password"
        }' | jq -r '.access_token')
else
    echo "Warning: Could not register HIS user, trying login..."
    HIS_TOKEN=$(curl -sf -X POST "${MATRIX_SERVER}/_matrix/client/v3/login" \
        -H "Content-Type: application/json" \
        -d '{
            "type": "m.login.password",
            "user": "his_user",
            "password": "his_password"
        }' | jq -r '.access_token')
fi

# Register GP user
echo "Registering GP user..."
GP_RESPONSE=$(curl -sf -X POST "${MATRIX_SERVER}/_matrix/client/v3/register" \
    -H "Content-Type: application/json" \
    -d '{
        "username": "gp_user",
        "password": "gp_password",
        "auth": {
            "type": "m.login.dummy"
        }
    }' 2>/dev/null || true)

if echo "$GP_RESPONSE" | grep -q "access_token"; then
    GP_TOKEN=$(echo "$GP_RESPONSE" | jq -r '.access_token')
    echo "GP user registered successfully"
elif echo "$GP_RESPONSE" | grep -q "M_USER_IN_USE"; then
    echo "GP user already exists, logging in..."
    GP_TOKEN=$(curl -sf -X POST "${MATRIX_SERVER}/_matrix/client/v3/login" \
        -H "Content-Type: application/json" \
        -d '{
            "type": "m.login.password",
            "user": "gp_user",
            "password": "gp_password"
        }' | jq -r '.access_token')
else
    echo "Warning: Could not register GP user, trying login..."
    GP_TOKEN=$(curl -sf -X POST "${MATRIX_SERVER}/_matrix/client/v3/login" \
        -H "Content-Type: application/json" \
        -d '{
            "type": "m.login.password",
            "user": "gp_user",
            "password": "gp_password"
        }' | jq -r '.access_token')
fi

# Create messaging room
echo "Creating messaging room..."
ROOM_RESPONSE=$(curl -sf -X POST "${MATRIX_SERVER}/_matrix/client/v3/createRoom" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer ${HIS_TOKEN}" \
    -d '{
        "room_alias_name": "messaging",
        "name": "AT FHIR Messaging Channel",
        "topic": "Shared FHIR R5 messaging channel for HIS, GP, and Pharmacy",
        "preset": "public_chat",
        "visibility": "public"
    }' 2>/dev/null || true)

if echo "$ROOM_RESPONSE" | grep -q "room_id"; then
    ROOM_ID=$(echo "$ROOM_RESPONSE" | jq -r '.room_id')
    echo "Room created: ${ROOM_ID}"
else
    echo "Room may already exist, looking up..."
    ROOM_ID=$(curl -sf "${MATRIX_SERVER}/_matrix/client/v3/directory/room/%23messaging:${MATRIX_SERVER_NAME}" \
        -H "Authorization: Bearer ${HIS_TOKEN}" | jq -r '.room_id' 2>/dev/null || echo "")
    if [ -n "$ROOM_ID" ] && [ "$ROOM_ID" != "null" ]; then
        echo "Found existing room: ${ROOM_ID}"
    else
        echo "Could not create or find room"
    fi
fi

# Join GP user to the room
if [ -n "$ROOM_ID" ] && [ "$ROOM_ID" != "null" ]; then
    echo "Joining GP user to room..."
    curl -sf -X POST "${MATRIX_SERVER}/_matrix/client/v3/join/${ROOM_ID}" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer ${GP_TOKEN}" \
        -d '{}' > /dev/null 2>&1 || true
    echo "GP user joined room"
fi

# Register Pharmacy user
echo "Registering Pharmacy user..."
PHARMACY_RESPONSE=$(curl -sf -X POST "${MATRIX_SERVER}/_matrix/client/v3/register" \
    -H "Content-Type: application/json" \
    -d '{
        "username": "pharmacy_user",
        "password": "pharmacy_password",
        "auth": {
            "type": "m.login.dummy"
        }
    }' 2>/dev/null || true)

if echo "$PHARMACY_RESPONSE" | grep -q "access_token"; then
    PHARMACY_TOKEN=$(echo "$PHARMACY_RESPONSE" | jq -r '.access_token')
    echo "Pharmacy user registered successfully"
elif echo "$PHARMACY_RESPONSE" | grep -q "M_USER_IN_USE"; then
    echo "Pharmacy user already exists, logging in..."
    PHARMACY_TOKEN=$(curl -sf -X POST "${MATRIX_SERVER}/_matrix/client/v3/login" \
        -H "Content-Type: application/json" \
        -d '{
            "type": "m.login.password",
            "user": "pharmacy_user",
            "password": "pharmacy_password"
        }' | jq -r '.access_token')
else
    echo "Warning: Could not register Pharmacy user, trying login..."
    PHARMACY_TOKEN=$(curl -sf -X POST "${MATRIX_SERVER}/_matrix/client/v3/login" \
        -H "Content-Type: application/json" \
        -d '{
            "type": "m.login.password",
            "user": "pharmacy_user",
            "password": "pharmacy_password"
        }' | jq -r '.access_token')
fi

# Join Pharmacy user to the room
if [ -n "$ROOM_ID" ] && [ "$ROOM_ID" != "null" ]; then
    echo "Joining Pharmacy user to room..."
    curl -sf -X POST "${MATRIX_SERVER}/_matrix/client/v3/join/${ROOM_ID}" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer ${PHARMACY_TOKEN}" \
        -d '{}' > /dev/null 2>&1 || true
    echo "Pharmacy user joined room"
fi

echo ""
echo "========================================="
echo "Matrix setup complete!"
echo "HIS User: @his_user:${MATRIX_SERVER_NAME}"
echo "GP User: @gp_user:${MATRIX_SERVER_NAME}"
echo "Pharmacy User: @pharmacy_user:${MATRIX_SERVER_NAME}"
echo "Room: #messaging:${MATRIX_SERVER_NAME}"
if [ -n "$ROOM_ID" ]; then
    echo "Room ID: ${ROOM_ID}"
fi
echo "========================================="

