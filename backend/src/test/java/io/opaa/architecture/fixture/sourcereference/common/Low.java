package io.opaa.architecture.fixture.sourcereference.common;

import static io.opaa.architecture.fixture.sourcereference.observability.High.LIMIT;

/**
 * A class in the lowest layer that is bounded by the same limit as {@link
 * io.opaa.architecture.fixture.sourcereference.observability.High}.
 *
 * @see io.opaa.architecture.fixture.sourcereference.observability.High
 */
public class Low {

  int limit = LIMIT;
}
