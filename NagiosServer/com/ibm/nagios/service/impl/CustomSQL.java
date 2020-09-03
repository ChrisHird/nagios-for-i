package com.ibm.nagios.service.impl;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Map;

import com.ibm.as400.access.AS400;
import com.ibm.as400.access.AS400Message;
import com.ibm.as400.access.CommandCall;
import com.ibm.nagios.service.Action;
import com.ibm.nagios.util.CommonUtil;
import com.ibm.nagios.util.JDBCConnection;
import com.ibm.nagios.util.Constants;
import com.ibm.nagios.util.CustomBean;
import com.ibm.nagios.util.CustomCommand;
import com.ibm.nagios.util.CustomPluginFactory;

public class CustomSQL implements Action {
    public CustomSQL() {
    }

    CommandCall cmd = null;
    
    public int execute(AS400 as400, Map<String, String> args, StringBuffer response) {
        Statement stmt = null;
        ResultSet rs = null;
        int index = 1;    //for multi value's id field
        int count = 0;    //for record result number
        String outputValue = "";
        ArrayList<String[]> columns = new ArrayList<String[]>();
        String func = args.get("-F");
        String warningCap = args.get("-W");
        String criticalCap = args.get("-C");
        int returnValue = Constants.UNKNOWN;

        if (func == null) {
            response.append("The argument -F [function name] is not set");
            return returnValue;
        }

        Connection connection = null;
        try {
            JDBCConnection JDBCConn = new JDBCConnection();
            connection = JDBCConn.getJDBCConnection(as400.getSystemName(), args.get("-U"), args.get("-P"), args.get("-SSL"));
            if (connection == null) {
                response.append(Constants.retrieveDataError + " - " + "Cannot get the JDBC connection");
                return returnValue;
            }
            CustomBean custBean = CustomPluginFactory.get(func);
            if (custBean == null) {
                response.append(Constants.retrieveDataError + " - " + "function definition not found: " + func);
                return returnValue;
            }
            //if threshold not set by service, load the configuration from CustomSQL.xml
            if (warningCap == null) {
                warningCap = custBean.warning;
            }
            if (criticalCap == null) {
                criticalCap = custBean.critical;
            }

            stmt = connection.createStatement();

            //call the pre commands
            if (!custBean.preCmd.isEmpty()) {
                for (CustomCommand command : custBean.preCmd) {
                    if (command.getType().equalsIgnoreCase("CL")) {
                        if (!processClCommand(as400, command.getCommand(), response)) {
                            return returnValue;
                        }
                    } else if (command.getType().equalsIgnoreCase("SQL")) {
                        stmt.executeQuery(custBean.sqlCmd);
                    }
                }
            }

            rs = stmt.executeQuery(custBean.sqlCmd);
            if (rs == null) {
                response.append(Constants.retrieveDataError + " - " + "Cannot retrieve data from server");
                return returnValue;
            }
            ResultSetMetaData rsmtadta = rs.getMetaData();
            int colCount = rsmtadta.getColumnCount();
            for (int i = 1; i <= colCount; i++) {
                String colName = rsmtadta.getColumnName(i);
                String colType = rsmtadta.getColumnTypeName(i);
                columns.add(new String[]{colName, colType});
            }
            if (custBean.type.equalsIgnoreCase("single-value")) {
                if (rs.next()) {
                    if (columns.size() == 1) {
                        String colName = columns.get(0)[0];
                        String colType = columns.get(0)[1];
                        if (colType.equalsIgnoreCase("INTEGER")) {
                            int intWarningCap = (warningCap == null) ? 100 : Integer.parseInt(warningCap);
                            int intCriticalCap = (criticalCap == null) ? 100 : Integer.parseInt(criticalCap);
                            int value = rs.getInt(colName);

                            returnValue = CommonUtil.getStatus(value, intWarningCap, intCriticalCap, returnValue);
                            response.append(custBean.commonName + ": " + value + " | '" + custBean.commonName + "' = " + value + ";" + intWarningCap + ";" + intCriticalCap);
                        } else if (colType.equalsIgnoreCase("DECIMAL")) {
                            double doubleWarningCap = (warningCap == null) ? 100 : Double.parseDouble(warningCap);
                            double doubleCriticalCap = (criticalCap == null) ? 100 : Double.parseDouble(criticalCap);
                            double value = rs.getDouble(colName);

                            returnValue = CommonUtil.getStatus(value, doubleWarningCap, doubleCriticalCap, returnValue);
                            response.append(custBean.commonName + ": " + value + " | '" + custBean.commonName + "' = " + value + ";" + doubleWarningCap + ";" + doubleCriticalCap);
                        }
                    } else {
                        response.append("Error: The type of single value only support to select 1 column\n");
                        response.append("SQL: ").append(custBean.sqlCmd);
                        return Constants.UNKNOWN;
                    }
                }
            } else if (custBean.type.equalsIgnoreCase("muti-value") || custBean.type.equalsIgnoreCase("multi-value")) {
                String idName = null;
                String colName = null;
                String colType = null;

                if (columns.size() == 1) {
                    colName = columns.get(0)[0];
                    colType = columns.get(0)[1];
                } else if (columns.size() == 2) {
                    idName = columns.get(0)[0];
                    colName = columns.get(1)[0];
                    colType = columns.get(1)[1];
                } else {
                    response.append("Error: The type of multiple value only support to select 1 or 2 columns\n");
                    response.append("SQL: ").append(custBean.sqlCmd);
                    return Constants.UNKNOWN;
                }
                while (rs.next()) {
                    String id = "";
                    if (idName != null) {
                        id = rs.getString(idName);
                    } else {
                        id = String.valueOf(index++);
                    }
                    if (colType.equalsIgnoreCase("INTEGER")) {
                        int maxVal = 0;
                        int intWarningCap = (warningCap == null) ? 100 : Integer.parseInt(warningCap);
                        int intCriticalCap = (criticalCap == null) ? 100 : Integer.parseInt(criticalCap);

                        int value = rs.getInt(colName);

                        if (value > maxVal) {
                            maxVal = value;
                            outputValue = String.valueOf(maxVal);
                        }
                        returnValue = CommonUtil.getStatus(value, intWarningCap, intCriticalCap, returnValue);
                        response.append("'" + id + "' = " + value + ";" + intWarningCap + ";" + intCriticalCap);
                    } else if (colType.equalsIgnoreCase("DECIMAL")) {
                        double maxVal = 0;
                        double doubleWarningCap = (warningCap == null) ? 100 : Double.parseDouble(warningCap);
                        double doubleCriticalCap = (criticalCap == null) ? 100 : Double.parseDouble(criticalCap);
                        double value = rs.getDouble(colName);

                        if (value > maxVal) {
                            maxVal = value;
                            outputValue = String.valueOf(maxVal);
                        }
                        returnValue = CommonUtil.getStatus(value, doubleWarningCap, doubleCriticalCap, returnValue);
                        response.append("'" + id + "' = " + value + ";" + doubleWarningCap + ";" + doubleCriticalCap);
                    }
                }
                response.insert(0, custBean.commonName + ": " + outputValue + " | ");
            } else if (custBean.type.equalsIgnoreCase("list")) {
                double intWarningCap = (warningCap == null) ? 1 : Double.parseDouble(warningCap);
                double intCriticalCap = (criticalCap == null) ? 1 : Double.parseDouble(criticalCap);
                while (rs.next()) {
                    for (int i = 0; i < columns.size(); i++) {
                        String colName = columns.get(i)[0];
                        String value = rs.getString(colName);
                        response.append(colName + ": " + value + " ");
                    }
                    response.append("\n");
                    count++;
                }

                returnValue = CommonUtil.getStatus(count, intWarningCap, intCriticalCap, returnValue);
                response.insert(0, custBean.commonName + " count of record: " + count + "\n");
            }

            //call the post commands
            if (!custBean.postCmd.isEmpty()) {
                for (CustomCommand command : custBean.postCmd) {
                    if (command.getType().equalsIgnoreCase("CL")) {
                        if (!processClCommand(as400, command.getCommand(), response)) {
                            return Constants.UNKNOWN;
                        }
                    } else if (command.getType().equalsIgnoreCase("SQL")) {
                        stmt.executeQuery(custBean.sqlCmd);
                    }
                }
            }
        } catch (Exception e) {
            response.append(Constants.retrieveDataException + " -  " + e.toString());
            CommonUtil.printStack(e.getStackTrace(), response);
            CommonUtil.logError(args.get("-H"), this.getClass().getName(), e.getMessage());
            e.printStackTrace();
        } finally {
            try {
                if (rs != null)
                    rs.close();
                if (stmt != null)
                    stmt.close();
                if (connection != null)
                    connection.close();
            } catch (SQLException e) {
                response.append(Constants.retrieveDataException + " -  " + e.toString());
                e.printStackTrace();
            }
        }
        return returnValue;
    }

    private boolean processClCommand(AS400 as400, String command, StringBuffer response) throws Exception {
        if (cmd == null) {
            cmd = new CommandCall(as400);
        }
        cmd.setCommand(command);
        boolean rc = cmd.run();
        if (!rc) {
            AS400Message[] msgList = cmd.getMessageList();
            response.append(Constants.retrieveDataError).append(" - ").append("Failed to call the command:").append(command).append("\n");
            for (AS400Message message : msgList) {
                response.append(message.getID()).append(":").append(message.getText()).append("\n");
            }
        }
        return rc;
    }
}