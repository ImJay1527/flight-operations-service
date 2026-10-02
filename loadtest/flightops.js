// k6 load test for flight-operations-service. Run it with ./loadtest/run.sh (k6 runs in Docker).
//
// Each virtual user picks a random instance (like a load balancer would) and a random read endpoint.
// All endpoints used here are answered with data from EVERY instance (scatter-gather over the peers),
// so they exercise the peer-to-peer path, not just the local DB.
import http from 'k6/http';
import { check } from 'k6';

const TARGETS = (__ENV.TARGETS || 'https://flightops-1:8083,https://flightops-2:8083').split(',');

export const options = {
    // the dev CA isn't in the k6 image; certificate checks are covered by the functional tests
    insecureSkipTLSVerify: true,
    scenarios: {
        reads: {
            executor: 'constant-vus',
            vus: Number(__ENV.VUS || 50),
            duration: __ENV.DURATION || '60s',
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.01'],
    },
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

const PATHS = [
    '/api/aircraft-utilization',
    '/api/aircraft-utilization/CS-TPA',
    '/api/fuel-efficiency/aircraft',
    '/api/fuel-efficiency/routes',
];

export function setup() {
    const res = http.post(`${TARGETS[0]}/api/auth/login`,
        JSON.stringify({ username: 'atcc', password: 'atcc123' }),
        { headers: { 'Content-Type': 'application/json' } });
    if (res.status !== 200) {
        throw new Error(`login failed: ${res.status} ${res.body}`);
    }
    return { token: res.json('token') };
}

export default function (data) {
    const base = TARGETS[Math.floor(Math.random() * TARGETS.length)];
    const path = PATHS[Math.floor(Math.random() * PATHS.length)];
    const res = http.get(base + path, {
        headers: { Authorization: `Bearer ${data.token}` },
        tags: { name: path },
    });
    check(res, { 'status 200': (r) => r.status === 200 });
}
