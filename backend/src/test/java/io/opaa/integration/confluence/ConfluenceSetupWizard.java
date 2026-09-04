package io.opaa.integration.confluence;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Walks Confluence Data Center's first-run wizard over plain HTTP, the way <a
 * href="https://github.com/criew/atlassian-dc-plugin">criew/atlassian-dc-plugin</a>'s {@code
 * dev/auto_setup.py} does: licence first, then whatever form the wizard shows next - the cluster
 * step (skipped, but every one of its fields must be posted), the database step (the container's
 * environment already configured it), user management (internal), the administrator account,
 * finish. Two documented traps: continue from the URL a POST landed on, never from the wizard root;
 * and the cluster step rejects a form missing any of its fields.
 */
final class ConfluenceSetupWizard {

  private static final Logger log = LoggerFactory.getLogger(ConfluenceSetupWizard.class);

  private static final Pattern FORM_ACTION = Pattern.compile("<form[^>]*action=\"([^\"]+)\"");
  private static final Pattern SUBMIT_BUTTON =
      Pattern.compile("<input[^>]*type=\"submit\"[^>]*name=\"([^\"]+)\"[^>]*value=\"([^\"]*)\"");
  private static final Pattern FORM_ERROR = Pattern.compile("class=\"error[^\"]*\"[^>]*>([^<]+)");

  private final ConfluenceHttpSession session;
  private final String adminUser;
  private final String adminPassword;

  ConfluenceSetupWizard(ConfluenceHttpSession session, String adminUser, String adminPassword) {
    this.session = session;
    this.adminUser = adminUser;
    this.adminPassword = adminPassword;
  }

  /**
   * @return {@code true} if the wizard had to be run, {@code false} if the instance was set up
   */
  boolean run(String licence) throws IOException, InterruptedException {
    ConfluenceHttpSession.Page root = session.get(session.base + "/");
    log.info("confluence root -> {} {}", root.status(), root.url());
    String at = root.url().toString();
    if (!at.contains("/setup/") && !at.contains("bootstrap")) {
      return false;
    }
    if (at.contains("selectsetupstep") || at.contains("dosetupstart")) {
      session.get(session.base + "/setup/selectsetuptype.action");
    }
    ConfluenceHttpSession.Page licencePage =
        session.get(session.base + "/setup/setuplicense.action");
    ConfluenceHttpSession.Page afterLicence =
        session.postForm(
            session.base + "/setup/dosetuplicense.action",
            ConfluenceHttpSession.fields(
                "atl_token",
                licencePage.atlToken(),
                "confLicenseString",
                licence,
                "setupTypeCustom",
                "Next"));
    log.info("confluence dosetuplicense -> {} {}", afterLicence.status(), afterLicence.url());
    String licenceUrl = afterLicence.url().toString();
    if (licenceUrl.contains("setuplicense") && !licenceUrl.contains("dosetuplicense")) {
      throw new IOException("licence rejected: " + errors(afterLicence.body()));
    }
    walkUntilDone(afterLicence.url().toString());
    return true;
  }

  private void walkUntilDone(String startUrl) throws IOException, InterruptedException {
    String next = startUrl;
    String last = null;
    int sameUrl = 0;
    for (int step = 0; step < 14; step++) {
      ConfluenceHttpSession.Page page = session.get(next);
      String url = page.url().toString();
      log.info("confluence wizard step {} at {}", step, url);
      if (!url.contains("/setup/")) {
        return;
      }
      sameUrl = url.equals(last) ? sameUrl + 1 : 0;
      last = url;
      if (page.body().contains("Oops - an error has occurred")
          || page.body().contains("<title>Oops")) {
        throw new IOException("confluence wizard in error state at " + url);
      }
      Matcher action = FORM_ACTION.matcher(page.body());
      if (!action.find()) {
        if (sameUrl >= 1) {
          throw new IOException("confluence wizard stuck at " + url + " without a form");
        }
        Thread.sleep(3000);
        next = url;
        continue;
      }
      String target = action.group(1).replace("&amp;", "&");
      if (!target.startsWith("http")) {
        target =
            session.base + "/setup/" + target.replaceFirst("^/+", "").replaceFirst("^setup/", "");
      }
      Map<String, String> body = formBody(url, page.body(), page.atlToken());
      log.info("confluence wizard posting {} fields to {}", body.size(), target);
      ConfluenceHttpSession.Page result = session.postForm(target, body);
      log.info("confluence wizard -> {} {}", result.status(), result.url());
      String landed = result.url().toString();
      if (!landed.contains("/setup/")) {
        return;
      }
      if (landed.equals(url) && sameUrl >= 1) {
        throw new IOException(
            "confluence wizard loops at " + url + "; page errors: " + errors(result.body()));
      }
      next = landed;
    }
    throw new IOException("confluence wizard did not complete after 14 steps");
  }

  private Map<String, String> formBody(String url, String html, String token) {
    String u = url.toLowerCase();
    Map<String, String> body = new LinkedHashMap<>();
    body.put("atl_token", token);
    if (u.contains("setupcluster") || u.contains("setupchoosecluster")) {
      body.put("clusterName", "");
      body.put("clusterHome", "");
      body.put("networkInterface", "eth0");
      body.put("joinMethod", "multicast");
      body.put("generateClusterAddress", "auto");
      body.put("generateClusterAddressSubmitted", "submitted");
      body.put("clusterAddressString", "");
      body.put("clusterPeersString", "");
      body.put("awsAuthMethod", "iamrole");
      body.put("iamRole", "");
      body.put("accessKey", "");
      body.put("secretKey", "");
      body.put("region", "");
      body.put("hostHeader", "");
      body.put("securityGroupName", "");
      body.put("tagKey", "");
      body.put("tagValue", "");
      body.put("newCluster", "skipCluster");
      return body;
    }
    if (u.contains("setupdb") || u.contains("selectdatabase") || u.contains("setupdbtype")) {
      Matcher submit = SUBMIT_BUTTON.matcher(html);
      if (submit.find()) {
        body.put(submit.group(1), submit.group(2).isEmpty() ? "Next" : submit.group(2));
      }
      return body;
    }
    if (u.contains("setupload")
        || u.contains("loaddata")
        || u.contains("setupstart")
        || u.contains("selectsetupstep")) {
      body.put("setupOption", "INSTALL");
      body.put("submit", "Next");
      return body;
    }
    if (u.contains("setupusermanagementchoice")) {
      body.put("userManagementChoice", "internal");
      body.put("internal", "Manage users and groups within Confluence");
      return body;
    }
    if (u.contains("setupadministrator") || u.contains("setupadminuser")) {
      body.put("username", adminUser);
      body.put("fullName", "Integration Admin");
      body.put("email", "admin@example.test");
      body.put("password", adminPassword);
      body.put("confirm", adminPassword);
      body.put("setup-next-button", "Next");
      return body;
    }
    if (u.contains("finishsetup") || u.contains("setupfinish") || u.contains("default.action")) {
      body.put("submit", "Finish");
      return body;
    }
    Matcher inputs =
        Pattern.compile("<input[^>]*name=\"([^\"]+)\"[^>]*value=\"([^\"]*)\"").matcher(html);
    while (inputs.find()) {
      String name = inputs.group(1);
      String value = inputs.group(2);
      if (name.equals("atl_token")
          || name.equals("submit")
          || value.contains("Setup")
          || name.toLowerCase().contains("setup")) {
        body.put(name, value);
      }
    }
    body.putIfAbsent("submit", "Next");
    return body;
  }

  private static String errors(String html) {
    Matcher m = FORM_ERROR.matcher(html);
    StringBuilder sb = new StringBuilder();
    int n = 0;
    while (m.find() && n++ < 5) {
      sb.append(m.group(1).strip()).append("; ");
    }
    return sb.toString();
  }
}
