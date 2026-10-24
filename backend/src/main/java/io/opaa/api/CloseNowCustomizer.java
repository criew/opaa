package io.opaa.api;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.apache.catalina.Context;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.apache.coyote.ActionCode;
import org.apache.coyote.CloseNowException;
import org.springframework.boot.tomcat.TomcatContextCustomizer;
import org.springframework.stereotype.Component;

/**
 * A request that ends with a {@link CloseNowException} after its response was committed has its
 * connection closed at once: no last chunk, and no error page included into the started body. A
 * streamed download the server ends on purpose thus breaks visibly for the client, and Tomcat logs
 * the exception at {@code DEBUG} only.
 */
@Component
public class CloseNowCustomizer implements TomcatContextCustomizer {

  @Override
  public void customize(Context context) {
    context.getPipeline().addValve(new CloseNowValve());
  }

  static final class CloseNowValve extends ValveBase {

    CloseNowValve() {
      super(true);
    }

    @Override
    public void invoke(Request request, Response response) throws IOException, ServletException {
      getNext().invoke(request, response);
      if (response.isCommitted()
          && request.getAttribute(RequestDispatcher.ERROR_EXCEPTION)
              instanceof CloseNowException closeNow) {
        response.getCoyoteResponse().action(ActionCode.CLOSE_NOW, closeNow);
      }
    }
  }
}
