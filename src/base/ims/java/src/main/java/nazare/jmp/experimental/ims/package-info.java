/**
 * Experimental, read-only Java equivalents of the IMS reads done by {@code IBACSUM} (CUSTACCS then
 * ACCOUNT) and {@code IBLOGIN} (CUSTOMER), using the IMS Universal JDBC driver ({@code com.ibm.ims:udb}).
 *
 * <p>Nothing here is referenced by the JMP controllers, the PSBs, or z/OS Connect. Every entry point
 * calls {@link nazare.jmp.experimental.ExperimentalFeatures#requireImsReadOnly()}.
 */
@Experimental
package nazare.jmp.experimental.ims;

import nazare.jmp.experimental.Experimental;
