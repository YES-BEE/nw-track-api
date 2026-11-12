# Frontend CORS setup and diagnosis

REST CORS is handled by Spring Security before JWT authentication for `/api/**`. Allowed preflight requests need no login token. Actual API calls still require the normal token, module permissions and resource scope.

Set this in the repository-root `.env` file on the backend server, replacing examples with the **frontend browser origin**:

```dotenv
CORS_ALLOWED_ORIGINS=http://localhost:5173,http://192.168.1.20:5173
```

An origin is scheme + hostname/IP + port, with no path or trailing slash. `localhost`, `127.0.0.1`, HTTP, HTTPS and different ports are different origins. Use the exact `Origin` request header shown in browser DevTools, not the API base URL unless the frontend is hosted there too. List multiple allowed origins separated by commas. Do not use the literal `null` origin; serve the HTML through a web server rather than opening file://.

Then rebuild/recreate the deployed API:

```powershell
deployment\windows\install.cmd start
```

A plain container restart does not apply updated Compose environment variables or build new code. `CORS_ALLOWED_ORIGINS` is passed to the API container separately from the Windows-managed WebSocket `ALLOWED_ORIGINS`. If omitted, REST falls back to `ALLOWED_ORIGINS`. For direct Java/Maven execution export the variable in that process environment; `.env` is loaded by Compose/deployment scripts, not automatically by Spring.

The API accepts GET, POST, PUT, PATCH, DELETE, HEAD and OPTIONS, with Authorization, Content-Type, Accept and X-Request-ID request headers. Preflight results may be cached for one hour. Authentication uses an explicitly supplied Bearer header; cookie credentials are disabled. Use fetch's default credentials setting or `credentials: 'omit'`; do not set `credentials: 'include'` / Axios `withCredentials: true` for this API.

```js
const response = await fetch(`${baseUrl}/api/v1/auth/login`, {
  method: 'POST',
  headers: {'Content-Type': 'application/json'},
  body: JSON.stringify({username, password, clientType: 'PC'})
});
// Protected calls add Authorization: `Bearer ${accessToken}`.
```

Do not add Access-Control-Allow-Origin as a frontend request header and do not use `mode: 'no-cors'`: neither fixes readable authenticated API access.

## Check the preflight independently

Replace the example backend IP and frontend origin with your actual addresses:

```powershell
curl.exe -i -X OPTIONS "http://192.168.1.10/api/v1/auth/login" -H "Origin: http://localhost:5173" -H "Access-Control-Request-Method: POST" -H "Access-Control-Request-Headers: content-type,authorization"
```

Expect HTTP 200 and `Access-Control-Allow-Origin: http://localhost:5173`, with the requested method/headers allowed. Test a protected URL the same way: preflight still succeeds without a Bearer token; the actual call needs authentication.

- **403 / Invalid CORS request:** check exact origin, method and requested header names against the settings.
- **401 on OPTIONS / no allow-origin header:** the running API may still be the old build, or a proxy/security layer answered before Spring. Check response headers/status and backend logs.
- **502/504 or proxy-generated 403/429:** diagnose proxy/network/rate-limit errors first; such responses may lack application CORS headers. The login proxy has a rate limit; repeated tests can trigger it.
- **Mixed content:** an HTTPS frontend cannot call an HTTP API; use matching HTTPS deployment or an appropriate same-origin proxy.
- **Postman/Swagger works but browser fails:** browser CORS is the additional restriction. Postman success does not verify preflight handling.

Current Nginx templates forward OPTIONS and Origin to Spring; do not add a second conflicting CORS policy to the proxy. The integration tests verify allowed and denied origins, preflight without authentication, and CORS headers on both authenticated success and unauthenticated errors.
