package com.sistem.proje.protocol;

/**
 * GET komutu: GET <id>
 */
public class GetCommand extends Command {

    public GetCommand(String id) {
        super(id);
    }

    @Override
    public CommandType getType() {
        return CommandType.GET;
    }

    @Override
    public String toString() {
        return "GetCommand{id='" + id + "'}";
    }
}

