package io.opaa.query.spike;

import io.opaa.compliance.NoDataImpact;
import io.opaa.compliance.PerInstance;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The OpenSandbox server behind the tool {@code run_code} of the agent spike; development only and
 * off by default.
 *
 * @param enabled offers {@code run_code} to the tool loop; needs {@code
 *     opaa.spike.tool-loop.enabled} as well.
 * @param baseUrl address of the OpenSandbox server the sandboxes are created on.
 * @param apiKey API key of the OpenSandbox server.
 * @param image container image of a sandbox: Python with the document libraries.
 * @param runTimeout time limit of one script run.
 * @param cpu CPU limit of one sandbox, e.g. {@code 1} or {@code 500m}.
 * @param memory memory limit of one sandbox, e.g. {@code 1Gi}.
 */
@ConfigurationProperties(prefix = "opaa.sandbox")
@PerInstance("an experiment of this process")
@NoDataImpact("throwaway spike, development only, off by default")
record SandboxProperties(
    @NoDataImpact("throwaway spike, development only, off by default") boolean enabled,
    @NoDataImpact("the operator's own development sandbox server") String baseUrl,
    String apiKey,
    String image,
    @NoDataImpact("a time limit of one script run") Duration runTimeout,
    String cpu,
    String memory) {}
