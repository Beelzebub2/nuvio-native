// Some recent Samsung runtimes expose the wrt:service Service constructor
// instead of the legacy startService method. Starting may never settle even
// though the service is already listening; a separate health probe is required.
const START_GRACE_MS = 1500;

export function getWrtServiceClass(bridge = globalThis.__NUVIO_TIZEN_WRT_SERVICE__) {
  const Service = bridge?.Service || bridge?.default?.Service;
  return typeof Service === "function" ? Service : null;
}

export function startWithWrtServiceClass(Service, serviceId, graceMs = START_GRACE_MS) {
  let timer;
  return Promise.race([
    Promise.resolve().then(() => new Service(serviceId).start()),
    new Promise((resolve) => {
      timer = setTimeout(() => resolve("pending"), graceMs);
    })
  ]).finally(() => {
    if (timer) clearTimeout(timer);
  });
}
