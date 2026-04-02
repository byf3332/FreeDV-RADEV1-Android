package com.bg7yoz.ft8cn.connector;

import com.bg7yoz.ft8cn.x6100.X6100Radio;

public class X6100Connector extends BaseRigConnector {
    private final X6100Radio xieguRadio = new X6100Radio();

    public X6100Connector(int controlMode) {
        super(controlMode);
    }

    public X6100Radio getXieguRadio() {
        return xieguRadio;
    }
}
