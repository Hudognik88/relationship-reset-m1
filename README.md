# Relationship Reset M1

Static validation funnel for milestone M1: first 3 independent purchases.

## Current mode
- Sandbox/test only
- Stripe test checkout only
- $149 one-time
- No subscription or auto-renewal
- No backend database yet
- Raw diagnostic answers stay in browser session storage
- Stripe receives only an opaque client_reference_id plus non-sensitive attribution tags

## Deploy
Serve index.html as a static site.
