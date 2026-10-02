// PL3 p.21 "Performance Considerations": response time of LOCAL vs FORWARDED lookups.
// Run with ./loadtest/performance.sh latency
//
// setup(): creates 10 flights on each instance. Then every iteration looks up a random flight, either on the
// instance that stores it (local) or on the other one (forwarded), and records the time in its own metric.
// Light load (5 virtual users): this measures latency, not capacity (that is loadtest/run.sh).
import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';

const I1 = __ENV.I1 || 'https://flightops-1:8083';
const I2 = __ENV.I2 || 'https://flightops-2:8083';

const localMs = new Trend('lookup_local_ms', true);
const forwardedMs = new Trend('lookup_forwarded_ms', true);

export const options = {
    insecureSkipTLSVerify: true,
    vus: Number(__ENV.VUS || 5),
    duration: __ENV.DURATION || '60s',
    thresholds: {
        checks: ['rate>0.99'],
    },
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

function login() {
    const res = http.post(`${I1}/api/auth/login`, JSON.stringify({ username: 'admin', password: 'admin123' }),
        { headers: { 'Content-Type': 'application/json' } });
    return res.json('token');
}

function createFlights(base, aircraft, token, firstDay) {
    const numbers = [];
    for (let i = 0; i < 10; i++) {
        const dep = new Date(Date.now() + (firstDay + i * 3) * 86400000);
        const fmt = d => d.toISOString().substring(0, 19);
        const res = http.post(`${base}/api/scheduled-flights`, JSON.stringify({
            routeId: 'route-opo-lis', aircraftRegistration: aircraft,
            departureTime: fmt(dep), arrivalTime: fmt(new Date(dep.getTime() + 45 * 60000)),
        }), { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` } });
        if (res.status !== 201) throw new Error(`could not create a flight on ${base}: ${res.status} ${res.body}`);
        numbers.push(res.json('flightNumber'));
    }
    return numbers;
}

export function setup() {
    const token = login();
    const day = 100 + Math.floor(Math.random() * 2000);   // fresh dates, no overlap with earlier runs
    return {
        token,
        owners: [
            { base: I1, other: I2, flights: createFlights(I1, 'CS-TPA', token, day) },
            { base: I2, other: I1, flights: createFlights(I2, 'CS-TPB', token, day) },
        ],
    };
}

export default function (data) {
    const owner = data.owners[Math.floor(Math.random() * 2)];
    const flight = owner.flights[Math.floor(Math.random() * owner.flights.length)];
    const forwarded = Math.random() < 0.5;
    const res = http.get(`${forwarded ? owner.other : owner.base}/api/scheduled-flights/${flight}`,
        { headers: { Authorization: `Bearer ${data.token}` }, tags: { kind: forwarded ? 'forwarded' : 'local' } });

    const source = res.headers['X-Data-Source'] || '';
    check(res, {
        'status 200': r => r.status === 200,
        'answered local / forwarded as intended': () => forwarded ? source.startsWith('peer:') : source === 'local',
    });
    (forwarded ? forwardedMs : localMs).add(res.timings.duration);
}
