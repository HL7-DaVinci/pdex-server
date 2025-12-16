package com.lantanagroup.pdex.security;

public class UdapProperties {
    private String securityServerBase;
    private String certFile;
    private String certPass;

    public String getSecurityServerBase() {
        return securityServerBase;
    }

    public void setSecurityServerBase(String securityServerBase) {
        this.securityServerBase = securityServerBase;
    }

    public String getCertFile() {
        return certFile;
    }

    public void setCertFile(String certFile) {
        this.certFile = certFile;
    }

    public String getCertPass() {
        return certPass;
    }

    public void setCertPass(String certPass) {
        this.certPass = certPass;
    }
}
