package io.lightning.conduit.node;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Event {
    private int type;
    private Object payload;
    public void set(Object payload, int type) {
        this.payload = payload;
        this.type = type;
    }
}
