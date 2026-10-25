package de.oceanlabs.mcp.mcinjector;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

public class LogFormatter extends Formatter
{
    @Override
    public String format(LogRecord record)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(record.getLevel().getName());
        sb.append(": ");
        sb.append(this.formatMessage(record));
        sb.append("\n");
        if (record.getThrown() != null)
        {
            StringWriter sw = new StringWriter();
            record.getThrown().printStackTrace(new PrintWriter(sw));
            sb.append(sw.toString());
        }
        return sb.toString();
    }

}
