// Generates postman/thermogrid.postman_collection.json. Run: node postman/gen-collection.mjs
import fs from "node:fs";

const status = (n) => [`pm.test("status ${n}", function () { pm.response.to.have.status(${n}); });`];
const req = (name, method, path, o = {}) => ({
  name,
  event: o.tests ? [{ listen: "test", script: { type: "text/javascript", exec: o.tests } }] : [],
  request: {
    method,
    header: [...(o.body ? [{ key: "Content-Type", value: "application/json" }] : []), ...(o.headers || [])],
    auth: o.auth
      ? { type: "bearer", bearer: [{ key: "token", value: "{{" + o.auth + "}}", type: "string" }] }
      : { type: "noauth" },
    url: "{{gateway}}" + path,
    ...(o.body ? { body: { mode: "raw", raw: o.body } } : {}),
  },
});
const login = (user, pwVar, tokVar) =>
  req("Login " + user, "POST", "/api/auth/login", {
    body: JSON.stringify({ username: user, password: "{{" + pwVar + "}}" }),
    tests: [...status(200), `pm.collectionVariables.set("${tokVar}", pm.response.json().token);`],
  });

const prefixes = [
  ["topology", "/api/topology"], ["sim", "/api/sim/state"], ["telemetry", "/api/telemetry/racks/latest"],
  ["alerts", "/api/alerts?state=ACTIVE&limit=50"], ["workloads", "/api/workloads"],
  ["scheduler", "/api/scheduler/status"], ["cooling", "/api/cooling/units"], ["platform", "/api/platform/services"],
];

const collection = {
  info: {
    name: "ThermoGrid gateway tests",
    description: "Phase 10. Run the folders in order with the Collection Runner. Set gateway, viewerPassword, operatorPassword and adminPassword in your local environment. No secret is stored in this file.",
    schema: "https://schema.getpostman.com/json/collection/v2.1.0/collection.json",
  },
  variable: ["gateway:http://localhost:8080", "viewerPassword:", "operatorPassword:", "adminPassword:",
    "viewerToken:", "operatorToken:", "adminToken:", "workloadId:"].map((s) => {
    const i = s.indexOf(":"); return { key: s.slice(0, i), value: s.slice(i + 1) };
  }),
  item: [
    { name: "1 Authentication", item: [
      login("viewer", "viewerPassword", "viewerToken"),
      login("operator", "operatorPassword", "operatorToken"),
      login("admin", "adminPassword", "adminToken"),
      req("A-01 wrong password", "POST", "/api/auth/login", { body: '{"username":"viewer","password":"wrong"}', tests: status(401) }),
      req("S-01 no token", "GET", "/api/topology", { tests: status(401) }),
      req("S-02 garbage token", "GET", "/api/topology", { headers: [{ key: "Authorization", value: "Bearer not.a.jwt" }], tests: status(401) }),
      req("Me (viewer)", "GET", "/api/auth/me", { auth: "viewerToken", tests: [...status(200),
        'pm.test("role VIEWER", function () { pm.expect(pm.response.json().role).to.eql("VIEWER"); });'] }),
    ] },
    { name: "2 Roles", item: [
      req("S-03 VIEWER POST workload", "POST", "/api/workloads", { auth: "viewerToken", body: '{"name":"denied","cpuDemand":10,"priority":"LOW"}', tests: status(403) }),
      req("S-04 OPERATOR sim control", "POST", "/api/sim/control", { auth: "operatorToken", body: '{"action":"PAUSE"}', tests: status(403) }),
      req("S-04 OPERATOR seed-demo", "POST", "/api/workloads/seed-demo", { auth: "operatorToken", tests: status(403) }),
      req("S-06 forged X-User-Role", "POST", "/api/sim/control", { auth: "viewerToken", body: '{"action":"PAUSE"}', headers: [{ key: "X-User-Role", value: "ADMIN" }], tests: status(403) }),
      req("S-07 /internal via gateway", "GET", "/internal/topology", { auth: "adminToken", tests: status(404) }),
    ] },
    { name: "3 Route prefixes (A-05)", item: prefixes.map(([n, p]) => req("GET " + n, "GET", p, { auth: "viewerToken", tests: status(200) })) },
    { name: "4 Workloads (A-02, I-01)", item: [
      req("cpuDemand 0", "POST", "/api/workloads", { auth: "operatorToken", body: '{"name":"bad","cpuDemand":0,"priority":"LOW"}', tests: status(400) }),
      req("cpuDemand 101", "POST", "/api/workloads", { auth: "operatorToken", body: '{"name":"bad","cpuDemand":101,"priority":"LOW"}', tests: status(400) }),
      req("Create valid workload", "POST", "/api/workloads", { auth: "operatorToken", body: '{"name":"postman-check","cpuDemand":10,"priority":"LOW"}', tests: [...status(201), 'pm.collectionVariables.set("workloadId", pm.response.json().id);'] }),
      req("Read it (RUNNING once placed; re-send if still PENDING)", "GET", "/api/workloads/{{workloadId}}", { auth: "viewerToken", tests: status(200) }),
      req("Remove it", "DELETE", "/api/workloads/{{workloadId}}", { auth: "operatorToken", tests: ['pm.test("removed", function () { pm.expect(pm.response.code).to.be.oneOf([200, 204]); });'] }),
    ] },
  ],
};
fs.writeFileSync(new URL("./thermogrid.postman_collection.json", import.meta.url), JSON.stringify(collection, null, 2) + "\n");
