package io.lightning.conduit.node;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Event<T> {
    private int type;
    private T payload;

    public void set(T payload, int type) {
        this.payload = payload;
        this.type = type;
    }
}
