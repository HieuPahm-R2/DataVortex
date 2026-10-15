const sql = require("includes/campaign_sql");

function render(text) {
  const project = dataform.projectConfig.vars.project_id;
  const dataset = dataform.projectConfig.vars.dataset;
  if (!/^[a-z][a-z0-9-]{4,61}[a-z0-9]$/.test(project) ||
      !/^[A-Za-z_][A-Za-z0-9_]{0,1023}$/.test(dataset)) {
    throw new Error("Explicit valid project_id and dataset are required");
  }
  return text.split("YOUR_PROJECT.adtech").join(`${project}.${dataset}`);
}

exports.quality = () => render(sql.quality);
exports.publish = () => render(sql.publish);
