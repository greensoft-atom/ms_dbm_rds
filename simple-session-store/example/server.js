const http = require("node:http");
const crypto = require("node:crypto");
const path = require("node:path");
const { SessionStore } = require("../src");

const store = new SessionStore({
    file: path.join(__dirname, "../data/sessions.jsonl"),
    flushInterval: 1000,
    cleanupInterval: 1000,
    snapshotInterval: 60_000
});

function parseCookies(header) {
    const result = {};

    if (!header) return result;

    for (const part of header.split(";")) {
        const index = part.indexOf("=");

        if (index === -1) continue;

        const key = part.slice(0, index).trim();
        const value = part.slice(index + 1).trim();

        result[key] = decodeURIComponent(value);
    }

    return result;
}

function sendJson(res, statusCode, body) {
    const payload = JSON.stringify(body);

    res.writeHead(statusCode, {
        "Content-Type": "application/json; charset=utf-8",
        "Content-Length": Buffer.byteLength(payload)
    });

    res.end(payload);
}

async function main() {
    await store.start();

    const server = http.createServer((req, res) => {
        try {
            const cookies = parseCookies(req.headers.cookie);
            let sessionId = cookies.sid;

            if (!sessionId) {
                sessionId = crypto.randomBytes(24).toString("hex");

                store.set(
                    sessionId,
                    {
                        visits: 0,
                        createdBy: "example-server"
                    },
                    { ttl: 3600 }
                );

                res.setHeader(
                    "Set-Cookie",
                    `sid=${encodeURIComponent(sessionId)}; HttpOnly; Path=/; Max-Age=3600`
                );
            }

            let session = store.get(sessionId);

            if (!session) {
                session = {
                    visits: 0,
                    createdBy: "example-server"
                };

                store.set(sessionId, session, { ttl: 3600 });

                res.setHeader(
                    "Set-Cookie",
                    `sid=${encodeURIComponent(sessionId)}; HttpOnly; Path=/; Max-Age=3600`
                );
            }

            session.visits += 1;

            // set() is used here because the value returned by get() is the
            // stored object itself in this simple implementation.
            store.set(sessionId, session, { ttl: 3600 });

            if (req.url === "/") {
                return sendJson(res, 200, {
                    message: "Hello",
                    sessionId,
                    session
                });
            }

            if (req.url === "/stats") {
                return sendJson(res, 200, {
                    sessions: store.size(),
                    keys: store.keys()
                });
            }

            if (req.url === "/destroy") {
                store.delete(sessionId);

                res.setHeader(
                    "Set-Cookie",
                    "sid=; HttpOnly; Path=/; Max-Age=0"
                );

                return sendJson(res, 200, {
                    destroyed: true
                });
            }

            return sendJson(res, 404, {
                error: "Not found"
            });
        } catch (err) {
            console.error(err);
            sendJson(res, 500, { error: "Internal server error" });
        }
    });

    server.listen(3000, () => {
        console.log("Example server listening on http://localhost:3000");
    });

    const shutdown = async signal => {
        console.log(`Received ${signal}. Shutting down...`);

        server.close(async () => {
            try {
                await store.close();
                process.exit(0);
            } catch (err) {
                console.error(err);
                process.exit(1);
            }
        });
    };

    process.once("SIGINT", () => shutdown("SIGINT"));
    process.once("SIGTERM", () => shutdown("SIGTERM"));
}

main().catch(err => {
    console.error(err);
    process.exit(1);
});
