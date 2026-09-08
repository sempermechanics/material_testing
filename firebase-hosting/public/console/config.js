/* Deploy-time configuration.
 *
 * `__API_BASE_URL__` is substituted before deploying, the same way
 * backend/gateway/openapi.yaml substitutes __CLOUD_RUN_URL__ — see the README
 * in this directory. Never commit a live hostname here.
 */
export const API_BASE_URL = "__API_BASE_URL__";
