package uk.gov.dwp.uc.dip.schemagenerator.transformtable;

import uk.gov.dwp.uc.dip.mappingreader.TechnicalMapping;
import uk.gov.dwp.uc.dip.mappingreader.TechnicalMappingReader;
import uk.gov.dwp.uc.dip.schemagenerator.common.JsonPathUtils;
import uk.gov.dwp.uc.dip.schemagenerator.common.PathSplitByIndexOperatorInfo;
import uk.gov.dwp.uc.dip.schemagenerator.common.TechnicalMappingJSONFieldSchema;

import java.util.*;

import org.apache.log4j.Logger;

import static uk.gov.dwp.uc.dip.mappingreader.MappingTypeEnum.*;
import static uk.gov.dwp.uc.dip.mappingreader.MappingTypeEnum.SOURCE_TYPE_STRING;

/**
 * Create transform table as a select from source table.
 */
public class TransformTableGenerator {
    /**
     * Implemented only here to facilitate DP-2587. Should be global flag.
     */
    boolean removedEnabled=true;

    final static Logger logger = Logger.getLogger(TransformTableGenerator.class.getName());

    final static String REMOVED = "_removed.";

    public List<String> generateSqlForTable(TechnicalMappingReader techMap,
                                      String targetTable,
                                      String sourceTableName) {
        List<String> result = new ArrayList<>();

        // get all rules related to the target table
        List<TechnicalMapping> targetRules = techMap.getTargetColumns(targetTable);

        result.add(String.format("DROP TABLE IF EXISTS %s",targetTable));
        //
        result.add(getTransformSQL(sourceTableName
                ,targetRules, targetTable));
        
        return result;
    }

    /**
     * Generates SQL from provided source table to provided target table
     * @param sourceTable
     * @param rules
     * @param targetTable
     * @return
     */
    private String getTransformSQL(String sourceTable, List<TechnicalMapping> rules, String targetTable) {

        logger.debug(String.format("source table:%s, target table: %s, removed enabled: %s",sourceTable,targetTable,removedEnabled));

        String selectSQL = "";
        String allExplodedSQL = "";
        // have unique explodeAliases
        HashMap<String,SelectAndExplode> mapExplodeAliases = new HashMap<>();

        // group rules together by target to coalesce and produce one target column
        HashMap<TechnicalMapping, List<TechnicalMapping>> columnGroups = TechnicalMappingReader.groupByTarget(rules);

        // iterate each target group and generate columns
        for(HashMap.Entry<TechnicalMapping,List<TechnicalMapping>> columnGroup: columnGroups.entrySet()) {
            String columnSQL="";

            //remember target file name
            String targetFileName = columnGroup.getKey().targetFieldName;
            //remember size to coalesce later
            int groupsPerColumn = columnGroup.getValue().size();

            // group similar jsonpaths together, this grouping is useful when we source more than one type from single source
            // as if there are more that 1 item in the group we will have to COALESCE  group of get_json_object()
            Map<String,List<TechnicalMapping>> sameJsonPathSourceGroups = JsonPathUtils.groupByJSONPath(columnGroup.getValue());

            // iterate each source group
            for(Map.Entry<String,List<TechnicalMapping>> sameJsonPathSourceGroupsEntry:sameJsonPathSourceGroups.entrySet()) {
                List<TechnicalMapping> sameJsonPathSourceGroup = sameJsonPathSourceGroupsEntry.getValue();

                //reverse the order as super jsonpath has to come last
                sameJsonPathSourceGroup.sort((t1, t2) ->
                        JsonPathUtils.compareJSONPathsDesc(t1.jsonPath,t2.jsonPath));
                String sourceColumnSQL = "";


                if (sameJsonPathSourceGroup.size() == 1 ) {
                    // Different source target group.
                    // Iterate each rule within the source group
                    for (TechnicalMapping rule : sameJsonPathSourceGroup) {

                        SelectAndExplode selectAndExplode = getSelectAndExplode(rule);

                        // add backticks to the column path
                        String column = JsonPathUtils.addBackTicks(selectAndExplode.getSelectColumnsName());

                        // convert types
                        column = convertSourceToTargetHIVEType(rule, column);

                        //add function call if provided
                        column = decorateWithFunction(rule.function, column);

                        // Support for removed
                        if (removedEnabled) {
                            String removedColumn = JsonPathUtils.addBackTicks(createRemovedColumn(selectAndExplode));
                            removedColumn = convertSourceToTargetHIVEType(rule, removedColumn);
                            removedColumn = decorateWithFunction(rule.function, removedColumn);
                            // wrap with coalesce
                            column = coalesceRemovedColumn(column, removedColumn);
                        }

                        // gather all selects
                        if (sourceColumnSQL.length() > 0)
                            sourceColumnSQL += ", ";

                        sourceColumnSQL += column;

                        // add to exploded list
                        if (selectAndExplode.explodeAlias != null) {
                            // gather all explode aliases and filter out duplicates
                            mapExplodeAliases.put(selectAndExplode.explodeAlias, selectAndExplode);
                            if (removedEnabled) {
                                // add in mapping for removed data
                                SelectAndExplode removed = createRemovedVersionOfSelectAndExplode(selectAndExplode);
                                mapExplodeAliases.put(removed.explodeAlias, removed);
                            }
                        }
                    }
                }else {
                    // Same source target group.
                    // if there are more that one the same json in the source rules then all
                    // except the last item will be using get_json_object()
                    // Here we generate many sources
                    // EXAMPLE: (no removed)
                    // FUNCTION(
                    // COALESCE(
                    // get_json_object(COALESCE(`_removed`.`field1`, `field1` ),”$.a"),
                    // get_json_object(COALESCE(`_removed`.`field1`, `field1`),”$.b”),
                    // COALESCE(`_removed`.`field1`, `field1`))),

                    // get the last jsonpath as we will use it in get_json_object() many times
                    TechnicalMapping superPathRule = sameJsonPathSourceGroup.get(sameJsonPathSourceGroup.size()-1);
                    sameJsonPathSourceGroup.remove(sameJsonPathSourceGroup.size()-1);

                    // add backticks to the column path
                    String column = JsonPathUtils.addBackTicks(superPathRule.jsonPath);

                    //create get json object once at the beginning
                    String getJsonObjectStatement;
                    if(removedEnabled){
                        String removedColumn = JsonPathUtils.addBackTicks(createRemovedColumn(superPathRule.jsonPath));
                        //removedColumn = convertSourceToTargetHIVEType(superPathRule, removedColumn);
                        // wrap with coalesce
                        getJsonObjectStatement = coalesceRemovedColumn(column, removedColumn);
                    }else{
                        getJsonObjectStatement = column;
                    }

                    getJsonObjectStatement = String.format("GET_JSON_OBJECT(%s",getJsonObjectStatement);

                    column ="";
                    for (TechnicalMapping rule : sameJsonPathSourceGroup) {
                        // gather all selects
                        if (column.length() > 0)
                            column += ", ";
                        // append hive json path "$. ..."
                        column += String.format("%s,\"$.%s\")"
                                ,getJsonObjectStatement
                                ,JsonPathUtils.subJSONPath(rule.jsonPath,JsonPathUtils.getSegments(superPathRule.jsonPath).size()));

                    }
                    column+=",";

                    column += JsonPathUtils.addBackTicks(createRemovedColumn(superPathRule.jsonPath));

                    column+=",";

                    column += JsonPathUtils.addBackTicks(superPathRule.jsonPath);

                    sourceColumnSQL += column;

                    // at the end coalesce everything as we have to choose from existing values
                    sourceColumnSQL = String.format("COALESCE(%s)", sourceColumnSQL);

                    // convert types
                    sourceColumnSQL = convertSourceToTargetHIVEType(superPathRule, sourceColumnSQL);

                    //add function call if provided
                    sourceColumnSQL = decorateWithFunction(superPathRule.function, sourceColumnSQL);
                }

                // gather all selects
                if (columnSQL.length() > 0)
                    columnSQL += ", ";
                columnSQL += "\n";
                columnSQL += sourceColumnSQL;
            }
            // coalesce when there is more than one source field
            if (groupsPerColumn > 1)
                columnSQL = String.format("COALESCE(%s)", columnSQL);
            // gather all selects
            if (selectSQL.length() > 0)
                selectSQL += ", ";
            // add alias
            selectSQL += String.format("%s as %s", columnSQL, targetFileName);
        }
        //lateral views
        for (HashMap.Entry<String, SelectAndExplode> entry : mapExplodeAliases.entrySet()) {
            SelectAndExplode explodeAlias = entry.getValue();
            if (allExplodedSQL.length() > 0)
                allExplodedSQL += " ";
            String explodeSQL;

            if(explodeAlias.pathSplitByIndexOperatorInfo.isMapPath) {

                explodeSQL = String.format("LATERAL VIEW OUTER EXPLODE(%s) view_%s AS %s_key, %s_value \n"
                        , JsonPathUtils.addBackTicks(explodeAlias.normalizedJson)
                        , explodeAlias.explodeAlias, explodeAlias.explodeAlias, explodeAlias.explodeAlias);

            }else {
                explodeSQL = String.format("LATERAL VIEW OUTER EXPLODE(%s) view_%s AS %s \n"
                        , JsonPathUtils.addBackTicks(explodeAlias.normalizedJson)
                        , explodeAlias.explodeAlias, explodeAlias.explodeAlias);
            }
            allExplodedSQL += explodeSQL;
        }

        if(allExplodedSQL.length()==0)
            allExplodedSQL = sourceTable;
        return String.format("CREATE TABLE %s AS SELECT \n %s FROM %s\n %s"
                , TechnicalMappingJSONFieldSchema.normalizeHIVEObjectName(targetTable), selectSQL, sourceTable, allExplodedSQL);
    }

    private SelectAndExplode createRemovedVersionOfSelectAndExplode(SelectAndExplode originalSelectAndExplode){
        return
                new SelectAndExplode(originalSelectAndExplode.selectColumn.replace(originalSelectAndExplode.explodeAlias, originalSelectAndExplode.explodeAlias + "Removed")
                        ,originalSelectAndExplode.explodeAlias + "Removed",
                        REMOVED + originalSelectAndExplode.normalizedJson
                        ,originalSelectAndExplode.pathSplitByIndexOperatorInfo);
    }

    private String coalesceRemovedColumn(String column, String removedColumn){
        return "COALESCE(" +
                removedColumn + ", " +
                column + ")";
    }

    private String createRemovedColumn(SelectAndExplode selectAndExplode){
        if(null == selectAndExplode.explodeAlias){
            return REMOVED + selectAndExplode.getSelectColumnsName();
        }else{
            // when exploding removed array we have to give different name than for not removed array hence +'Removed'
            return selectAndExplode.getSelectColumnsName().replace(selectAndExplode.explodeAlias
                    ,selectAndExplode.explodeAlias + "Removed");
        }
    }

    private String createRemovedColumn(String jsonPath){
        return REMOVED + jsonPath;
    }

    /*
    * Convert source sourceType into target Type by applying function/snippet to the column
    * Throws exception when function/snippet does not support source sourceType
    * It does not check source field format.
    *
    * Suported convertions:
    * - string->timestamp
    * - int -> timestamp
    * @param rule
    * @param column
    * @return
    */
    private static String convertSourceToTargetHIVEType(TechnicalMapping rule, String column) {

        final String ConvertStringToTimestampTemplate="CASE " +
                "WHEN SUBSTRING(%s, LENGTH(%s), 1) = 'Z' THEN " +
                "CAST(CONCAT(SUBSTR(%s, 1, 10), ' ', SUBSTR(%s, 12, 12)) AS TIMESTAMP) " +
                "ELSE CAST(CONCAT(FROM_UNIXTIME(UNIX_TIMESTAMP(CAST(CONCAT(SUBSTR(%s, 1, 10), ' ', SUBSTR(%s, 12, 8)) AS TIMESTAMP)) - " +
                "(CAST(SUBSTR(%s, 25, 2) AS BIGINT) * 3600),'yyyy-MM-dd HH:mm:ss'),'.'" +
                ", SUBSTR(%s, 21, 3)) AS TIMESTAMP) " +
                "END";

        final String ConvertIntToTimestampTemplate="CAST(TO_DATE(FROM_UNIXTIME(UNIX_TIMESTAMP(CAST(%s AS STRING), " +
                "'yyyyMMdd'))) AS DATE)";

        final String ConvertStringToBooleanTemplate =
                "CASE WHEN UPPER(%s) IN ('FALSE', 'NO', 'N', '0') THEN false " +
                        "WHEN %s IS NULL THEN NULL ELSE true END";
        //if source and target types are the same then we don't convert
        if(rule.sourceType == rule.targetType){
            return column;
        }

        // functions can change data types for example: size(array<string>) returns integer, each function must have input and output data type
        if(rule.function.length()!=0){
            return column;
        }

        if(rule.targetType == SOURCE_TYPE_TIMESTAMP && rule.sourceType == SOURCE_TYPE_STRING){
            return String.format(ConvertStringToTimestampTemplate,column,column,column,column,column,column,column,column);
        }

        if(rule.sourceType == SOURCE_TYPE_STRING && rule.targetType == SOURCE_TYPE_BOOL){
            return String.format(ConvertStringToBooleanTemplate, column, column);
        }

        if(rule.targetType == SOURCE_TYPE_DATE &&
            (rule.sourceType == SOURCE_TYPE_INT || rule.sourceType == SOURCE_TYPE_STRING)){
                return String.format(ConvertIntToTimestampTemplate,column);
        }

        // Return simple cast - assume types can be cast if we got here
        return String.format("CAST(%s as %s)", column, rule.getTargetType());
    }
    /*
        * Adds hive function if provided to columns name
        * @param column
        * @return
        */
    private static String decorateWithFunction(String function, String column){
        if(function!=null && function.length()>0)
            return String.format("%s(%s)", function, column);
        return column;
    }

    /*
       * Check each segment of technical mapping object jsonpath, if it's an array or map, then check if
       *   array:
          it's [*] then make explode SQL it and make select SQL
          it's [int] then just make select SQL
          map:
          [mk]
          [mv]
       * @param rule
       * @return if array[*] is present then we return select (First) and explode (Second)
       * if it's not present then we return jsonpath (First) and null (Second)
       */
    private SelectAndExplode getSelectAndExplode(TechnicalMapping rule){
        String selectSQL;
        PathSplitByIndexOperatorInfo splitPathByIndexOperator = JsonPathUtils.splitPathByIndexOperator(rule.jsonPath);

        // do we have split array
        if(splitPathByIndexOperator.indexOperatorFound){
            // create explode alias by concatenating json path from before [] operator
            String explodeAlias = String.format("exploded_%s", TechnicalMappingJSONFieldSchema.normalizeHIVEObjectName(splitPathByIndexOperator.leftJsonPath));
            // if right part (after []) exists then we use it for select SQL
            if(splitPathByIndexOperator.rightJsonPath.length()>0) {
                selectSQL = explodeAlias.concat(".").concat(splitPathByIndexOperator.rightJsonPath);
            }else{
                if(rule.function.length()==0)
                    //if right part (after[]) does not exists then we just use alias to create select
                    selectSQL = explodeAlias;
                else
                    //it's array with function
                    return new SelectAndExplode(splitPathByIndexOperator.leftJsonPath
                            , null, null, splitPathByIndexOperator);
            }
            return new SelectAndExplode(selectSQL,explodeAlias,splitPathByIndexOperator.leftJsonPath,splitPathByIndexOperator);
        }
        return new SelectAndExplode(rule.jsonPath, null, null, splitPathByIndexOperator);
    }

}