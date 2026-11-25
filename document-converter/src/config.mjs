export function positiveInteger(value, fallback) {
  const numeric = Number(value ?? fallback);
  return Number.isSafeInteger(numeric) && numeric > 0 ? numeric : fallback;
}
