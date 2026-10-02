// PL3 p.21 "99.9% target availability": what fraction of requests still succeeds while an instance fails?
// Run with ./loadtest/performance.sh availability  (it kills instance 2 at ~30 s and starts it again at ~50 s; startup takes ~27 s)
//
// A steady 20 requests/s for 150 s. Each request asks a random instance for a random flight (flights exist on both
// instances). Recorded:
//   ok_first_try          the instance asked first answered with the flight
//   ok_with_failover      same, but if the instance asked first could not be reached at all, the client tries the
//                         other instance (what a load balancer / smart client would do)
//   fail_instance_down    the instance asked could not be reached
//   fail_data_unavailable reachable instance, but the flight is stored on the dead instance -> 404
import http from 'k6/http';
import { Rate, Counter } from 'k6/metrics';

const I1 = __ENV.I1 || 'https://flightops-1:8083';
const I2 = __ENV.I2 || 'https://flightops-2:8083';
// LB set (./loadtest/performance.sh availability-lb): every request goes to the load balancer instead of a random
// instance; the flights are still created directly on each instance.
const LB = __ENV.LB || '';

const okFirstTry = new Rate('ok_first_try');
const okWithFailover = new Rate('ok_with_failover');
const failInstanceDown = new Counter('fail_instance_down');
const failDataUnavailable = new Counter('fail_data_unavailable');

// Every result is also tagged with its 10-second window (w=000, 010, ...). A threshold that always passes, per
// window, makes k6 print each window's rate in the summary: a timeline of before / during / after the outage.
const WINDOWS = 15;
const windowName = i => String(i * 10).padStart(3, '0');
const thresholds = {};
for (let i = 0; i < WINDOWS; i++) {
    thresholds[`ok_first_try{w:${windowName(i)}}`] = ['rate>=0'];
    thresholds[`ok_with_failover{w:${windowName(i)}}`] = ['rate>=0'];
}

export const options = {
    insecureSkipTLSVerify: true,
    scenarios: {
        steady: {
            executor: 'constant-arrival-rate',
            rate: Number(__ENV.RATE || 20),
            timeUnit: '1s',
            duration: __ENV.DURATION || '150s',
            preAllocatedVUs: 20,
            maxVUs: 100,
        },
    },
    thresholds,   // only used to print the per-window timeline; failures never abort this test
};

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
    const token = http.post(`${I1}/api/auth/login`, JSON.stringify({ username: 'admin', password: 'admin123' }),
        { headers: { 'Content-Type': 'application/json' } }).json('token');
    const day = 100 + Math.floor(Math.random() * 2000);
    const flights = createFlights(I1, 'CS-TPA', token, day).concat(createFlights(I2, 'CS-TPB', token, day));
    return { token, flights, start: Date.now() };
}

function ask(base, flight, token) {
    return http.get(`${base}/api/scheduled-flights/${flight}`,
        { headers: { Authorization: `Bearer ${token}` }, timeout: '5s' });
}

export default function (data) {
    const flight = data.flights[Math.floor(Math.random() * data.flights.length)];
    const [first, second] = LB ? [LB, LB] : (Math.random() < 0.5 ? [I1, I2] : [I2, I1]);

    const w = { w: windowName(Math.min(WINDOWS - 1, Math.floor((Date.now() - data.start) / 10000))) };

    let res = ask(first, flight, data.token);
    okFirstTry.add(res.status === 200, w);

    if (res.status === 0) {               // could not connect at all: instance down
        failInstanceDown.add(1);
        res = ask(second, flight, data.token);
    }
    okWithFailover.add(res.status === 200, w);
    if (res.status === 404) {
        failDataUnavailable.add(1);       // the flight lives on the dead instance
    }
}
