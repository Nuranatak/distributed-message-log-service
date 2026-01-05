package com.sistem.proje.protocol;

/**
 * SET komutu: SET <id> <message>
 */
public class SetCommand extends Command {
    private final String message;

    public SetCommand(String id, String message) {
        super(id);
        if (message == null) {
            throw new IllegalArgumentException("Message null olamaz");
        }
        this.message = message;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public CommandType getType() {
        return CommandType.SET;
    }

    @Override
    public String toString() {
        return "SetCommand{id='" + id + "', message='" + message + "'}";
    }
}

