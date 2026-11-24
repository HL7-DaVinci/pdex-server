package com.lantanagroup.pdex.proxy;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicAddressesTest {

	@ParameterizedTest
	@ValueSource(strings = {"0.0.0.1", "10.0.0.1", "100.64.0.1", "127.0.0.1", "169.254.169.254", "172.16.0.1",
			"192.168.1.1", "224.0.0.1", "255.255.255.255", "::1", "::", "fe80::1", "fec0::1", "ff02::1", "fd00::1",
			"::ffff:127.0.0.1", "::ffff:10.0.0.1", "::10.0.0.1", "::127.0.0.1", "fc00::1", "100.127.255.255",
			"172.31.255.255", "64:ff9b::a00:1", "64:ff9b::7f00:1", "64:ff9b::a9fe:a9fe", "64:ff9b:1::1",
			"2002:a00:1::1", "2002:7f00:1::1", "2001:0:1::1", "::ffff:0:7f00:1", "::ffff:0:808:808", "192.0.0.1",
			"198.18.0.1", "198.19.255.255", "64:ff9b:1::808:808", "64:ff9b:1:ffff:ffff:ffff:ffff:ffff",
			"2001:0:4136:e378:8000:63bf:3fff:fdd2", "64:ff9b::c0a8:101", "2002:c0a8:101::1"})
	void isNotPublic(String literal) throws Exception {
		assertFalse(PublicAddresses.isPublic(InetAddress.getByName(literal)));
	}

	@ParameterizedTest
	@ValueSource(strings = {"100.128.0.1", "172.32.0.1", "8.8.8.8", "2001:4860:4860::8888", "100.63.255.255",
			"172.15.255.255", "223.255.255.255", "64:ff9b::808:808", "2002:808:808::1", "2001:1::1", "192.0.1.1",
			"198.17.255.255", "198.20.0.1", "64:ff9a::a00:1", "64:ff9b::1:a00:1", "::ffff:1:a00:1"})
	void isPublic(String literal) throws Exception {
		assertTrue(PublicAddresses.isPublic(InetAddress.getByName(literal)));
	}

	@Test
	void mappedFormInsideInet6AddressIsNotPublic() throws Exception {
		// InetAddress.getByName returns an Inet4Address for a mapped literal, but Inet6Address.getByAddress keeps the IPv6 type.
		byte[] mapped = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff, 127, 0, 0, 1};
		assertFalse(PublicAddresses.isPublic(Inet6Address.getByAddress(null, mapped, -1)));
	}

	@Test
	void resolveRefusesLocalhost() {
		assertThrows(PublicAddresses.BlockedAddressException.class, () -> PublicAddresses.resolve("localhost"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"[::1]", "::1", "[::ffff:10.0.0.1]", "2130706433", ""})
	void resolveRefusesInternalLiterals(String host) {
		assertThrows(PublicAddresses.BlockedAddressException.class, () -> PublicAddresses.resolve(host));
	}

	@Test
	void requirePublicRefusesWhenAnyAddressIsNotPublic() throws Exception {
		InetAddress publicAddress = InetAddress.getByName("8.8.8.8");
		InetAddress privateAddress = InetAddress.getByName("10.0.0.1");
		assertThrows(PublicAddresses.BlockedAddressException.class,
				() -> PublicAddresses.requirePublic("h", new InetAddress[] {publicAddress, privateAddress}));
		assertThrows(PublicAddresses.BlockedAddressException.class,
				() -> PublicAddresses.requirePublic("h", new InetAddress[] {privateAddress, publicAddress}));
		InetAddress[] checked = {publicAddress};
		assertSame(checked, PublicAddresses.requirePublic("h", checked));
		UnknownHostException empty = assertThrows(UnknownHostException.class,
				() -> PublicAddresses.requirePublic("h", new InetAddress[0]));
		assertEquals(UnknownHostException.class, empty.getClass());
	}

	@Test
	void resolveReturnsTheCheckedPublicAddress() throws Exception {
		assertArrayEquals(new InetAddress[] {InetAddress.getByName("8.8.8.8")}, PublicAddresses.resolve("8.8.8.8"));
		InetAddress ipv6 = InetAddress.getByName("2001:4860:4860::8888");
		assertArrayEquals(new InetAddress[] {ipv6}, PublicAddresses.resolve("[2001:4860:4860::8888]"));
		assertArrayEquals(new InetAddress[] {ipv6}, PublicAddresses.resolve("2001:4860:4860::8888"));
	}
}
