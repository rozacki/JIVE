package uk.gov.dwp.uc.dip.jive;

import com.vaadin.data.Property;
import com.vaadin.ui.*;
import org.apache.log4j.Logger;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Created by chrisrozacki on 07/03/2017.
 */
public class MappingEditor extends Panel {
    private static final Logger log = Logger.getLogger(MainPanel.class);
    private final static ConsoleLogger consoleLogger = (ConsoleLogger) Logger.getLogger("ConsoleLogger", new Log4JFactory(JiveUI.Console));
    public final ComboBox databasesComboBox = new ComboBox("Database");
    public final ComboBox collectionsComboBox = new ComboBox("Collection");
    public final DateField dateTime = new DateField("Date");

    public MappingEditor() {
        super();
        final VerticalLayout mainLayout = new VerticalLayout();
        final HorizontalLayout schemaMappingLayout = new HorizontalLayout();
        final HorizontalLayout targetTableLayout = new HorizontalLayout();
        final Tree schemaTree = new Tree();
        final Table mappingGrid = new Table();
        final JSONSchemaLoader loadButton = new JSONSchemaLoader(this, "Load schema", schemaTree);
        final ComboBox currentTargetTable = new ComboBox("Select target table");
        final Button addTargetTable = new Button("Add target table");
        final TextField newTargetTable = new TextField("New target table");
        final DataSourceCatalouge catalouge = new DataSourceCatalouge();

        mappingGrid.setVisible(false);
        schemaTree.setVisible(true);
        addTargetTable.setVisible(false);
        newTargetTable.setVisible(false);
        currentTargetTable.setVisible(false);

        this.setContent(mainLayout);
        mainLayout.addComponent(targetTableLayout);
        targetTableLayout.addComponent(databasesComboBox);
        targetTableLayout.addComponent(collectionsComboBox);
        targetTableLayout.addComponent(dateTime);
        targetTableLayout.addComponent(loadButton);
        targetTableLayout.addComponent(currentTargetTable);
        targetTableLayout.addComponent(newTargetTable);
        targetTableLayout.addComponent(addTargetTable);
        schemaMappingLayout.addComponent(schemaTree);
        schemaMappingLayout.addComponent(mappingGrid);
        mainLayout.addComponent(schemaMappingLayout);
        mainLayout.addComponent(JiveUI.Console);

        // schema tree
        schemaTree.setCaption("Schema");
        mappingGrid.addContainerProperty("source DB", String.class, null);
        mappingGrid.addContainerProperty("source collection", String.class, null);
        mappingGrid.addContainerProperty("source field location", String.class, null);
        mappingGrid.addContainerProperty("destination table", String.class, null);
        mappingGrid.addContainerProperty("destination column", String.class, null);
        mappingGrid.addContainerProperty("destination data type", String.class, null);
        mappingGrid.addContainerProperty("destination function", String.class, null);
        mappingGrid.addContainerProperty("destination DQ", String.class, null);
        dateTime.setValue(new Date());

        databasesComboBox.addItems(catalouge.getDatabases());
        Property.ValueChangeListener listener = new Property.ValueChangeListener() {
            public void valueChange(Property.ValueChangeEvent event) {
                String targetTable = (String) databasesComboBox.getValue();
                log.debug("selected target database " + targetTable);
                log.debug("Change target table selection to " + targetTable);
                collectionsComboBox.removeAllItems();
                collectionsComboBox.addItems(catalouge.getCollections(targetTable));
            };
        };
        databasesComboBox.addValueChangeListener(listener);

        // set default values
        SimpleDateFormat dateFormat = new SimpleDateFormat("yy-MM-dd");
        dateTime.setData(dateFormat.format(java.util.Calendar.getInstance().getTime()));
        databasesComboBox.setValue(catalouge.getDatabases().toArray()[0]);
    }
}
