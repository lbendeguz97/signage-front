#!/bin/bash
# api_test/enroll_and_test.sh

set -e

# Default values
SERVER_URL="https://localhost:4000"
OTP=""

# Help message
show_help() {
    echo "Usage: $0 -o <otp> [-s <server_url>]"
    echo "  -o  OTP / Registration Key to use for enrollment"
    echo "  -s  Backend Server URL (default: $SERVER_URL)"
    exit 1
}

# Parse options
while getopts "o:s:h" opt; do
    case "$opt" in
        o) OTP=$OPTARG ;;
        s) SERVER_URL=$OPTARG ;;
        h|*) show_help ;;
    esac
done

if [ -z "$OTP" ]; then
    echo "❌ Error: OTP is required."
    show_help
fi

# Clean up any existing test certificates/keys
rm -f client_key.pem client_req.csr enroll_response.json client_cert.pem

echo "🔄 Generating private key (EC prime256v1)..."
openssl ecparam -name prime256v1 -genkey -noout -out client_key.pem

echo "🔄 Creating Certificate Signing Request (CSR)..."
openssl req -new -key client_key.pem -out client_req.csr -subj "/CN=api-test-device"

echo "🔄 Sending enrollment request to $SERVER_URL/enroll..."
# Escape newlines in CSR for JSON inclusion
CSR_JSON_SAFE=$(cat client_req.csr | sed ':a;N;$!ba;s/\n/\\n/g')

# POST request to /enroll
curl -k -s -X POST -H "Content-Type: application/json" \
  -d "{\"otp\":\"$OTP\", \"csr\":\"$CSR_JSON_SAFE\"}" \
  "$SERVER_URL/enroll" -o enroll_response.json

# Check response
if [ ! -f enroll_response.json ] || [ ! -s enroll_response.json ]; then
    echo "❌ Enrollment failed: Empty response from server."
    exit 1
fi

if grep -q "certificate" enroll_response.json; then
    echo "✅ Enrollment request completed. Extracting client certificate..."
    python3 -c "import json; print(json.load(open('enroll_response.json'))['certificate'])" > client_cert.pem
    echo "✅ Certificate saved to client_cert.pem"
else
    echo "❌ Enrollment failed. Server response:"
    cat enroll_response.json
    echo ""
    exit 1
fi

echo -e "\n🔒 Testing authenticated mTLS endpoints..."

echo -e "\n1. Testing GET /checkin (expecting 204 No Content):"
curl -k -i -s --cert client_cert.pem --key client_key.pem "$SERVER_URL/checkin"

echo -e "\n2. Testing GET /getConfig (expecting JSON configuration):"
curl -k -s --cert client_cert.pem --key client_key.pem "$SERVER_URL/getConfig" | python3 -m json.tool || cat getConfig_response.json

echo -e "\n3. Testing GET /getDatabaseStatus (expecting JSON database status):"
curl -k -s --cert client_cert.pem --key client_key.pem "$SERVER_URL/getDatabaseStatus" | python3 -m json.tool

echo -e "\n4. Testing GET /getAdStatus (expecting JSON ad status):"
curl -k -s --cert client_cert.pem --key client_key.pem "$SERVER_URL/getAdStatus" | python3 -m json.tool

echo -e "\n🎉 All tests completed!"
