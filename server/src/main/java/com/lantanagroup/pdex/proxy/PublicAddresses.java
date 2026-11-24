package com.lantanagroup.pdex.proxy;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * The guard of the passthrough client. It resolves a host one time and refuses the host unless every
 * resolved address is public.
 */
public final class PublicAddresses {

	// The leading bytes of the IPv6 prefixes that the guard examines.
	private static final int[] IPV4_COMPATIBLE = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}; // ::a.b.c.d, with :: and ::1
	private static final int[] IPV4_MAPPED = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff}; // ::ffff:a.b.c.d
	private static final int[] IPV4_TRANSLATED = {0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 0, 0}; // ::ffff:0:a.b.c.d
	private static final int[] NAT64_WELL_KNOWN = {0, 0x64, 0xff, 0x9b, 0, 0, 0, 0, 0, 0, 0, 0}; // 64:ff9b::/96
	private static final int[] NAT64_LOCAL_USE = {0, 0x64, 0xff, 0x9b, 0, 1}; // 64:ff9b:1::/48
	private static final int[] SIX_TO_FOUR = {0x20, 0x02}; // 2002::/16, IPv4 in bytes 2 to 5
	private static final int[] TEREDO = {0x20, 0x01, 0, 0}; // 2001::/32

	private PublicAddresses() {}

	/**
	 * Thrown for a host with an address that is not public. It extends UnknownHostException, because the
	 * DnsResolver contract of Apache HttpClient permits only that exception.
	 */
	public static final class BlockedAddressException extends UnknownHostException {
		private static final long serialVersionUID = 1L;

		public BlockedAddressException(String host, InetAddress address) {
			super("The address " + address.getHostAddress() + " of host " + host + " is not public");
		}
	}

	/**
	 * Accepts a name, an IPv4 literal, or an IPv6 literal with or without brackets. The result holds the
	 * address objects that were checked, so the client connects without a second lookup. It is never null
	 * or empty.
	 */
	public static InetAddress[] resolve(String host) throws UnknownHostException {
		return requirePublic(host, InetAddress.getAllByName(host));
	}

	static InetAddress[] requirePublic(String host, InetAddress[] addresses) throws UnknownHostException {
		if (addresses.length == 0) {
			throw new UnknownHostException(host);
		}
		for (InetAddress address : addresses) {
			if (!isPublic(address)) {
				throw new BlockedAddressException(host, address);
			}
		}
		return addresses;
	}

	public static boolean isPublic(InetAddress address) {
		byte[] bytes = address.getAddress();
		if (address instanceof Inet6Address) {
			// These forms carry an IPv4 address and follow its rules. DNS64 maps each public IPv4-only host into
			// 64:ff9b::/96, so a refusal of the whole prefix stops passthrough on IPv6-only networks.
			if (startsWith(bytes, IPV4_COMPATIBLE) || startsWith(bytes, IPV4_MAPPED)
					|| startsWith(bytes, NAT64_WELL_KNOWN)) {
				return isPublicIpv4(bytes, 12);
			}
			if (startsWith(bytes, SIX_TO_FOUR)) {
				return isPublicIpv4(bytes, 2);
			}
			if (startsWith(bytes, IPV4_TRANSLATED) || startsWith(bytes, NAT64_LOCAL_USE) || startsWith(bytes, TEREDO)) {
				return false;
			}
			// fc00::/7 unique local. isSiteLocalAddress covers only fec0::/10.
			if ((bytes[0] & 0xfe) == 0xfc) {
				return false;
			}
			return !(address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
					|| address.isSiteLocalAddress() || address.isMulticastAddress());
		}
		return isPublicIpv4(bytes, 0);
	}

	private static boolean startsWith(byte[] bytes, int[] prefix) {
		for (int i = 0; i < prefix.length; i++) {
			if ((bytes[i] & 0xff) != prefix[i]) {
				return false;
			}
		}
		return true;
	}

	// Examines the four bytes of an IPv4 address that start at offset.
	private static boolean isPublicIpv4(byte[] bytes, int offset) {
		int first = bytes[offset] & 0xff;
		int second = bytes[offset + 1] & 0xff;
		int third = bytes[offset + 2] & 0xff;
		return !(first == 0
				|| first == 10
				|| (first == 100 && (second & 0xc0) == 64) // 100.64.0.0/10
				|| first == 127
				|| (first == 169 && second == 254)
				|| (first == 172 && (second & 0xf0) == 16) // 172.16.0.0/12
				|| (first == 192 && second == 0 && third == 0)
				|| (first == 192 && second == 168)
				|| (first == 198 && (second & 0xfe) == 18) // 198.18.0.0/15
				|| first >= 224); // 224.0.0.0/3: multicast, reserved, and broadcast
	}
}
