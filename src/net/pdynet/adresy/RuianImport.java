package net.pdynet.adresy;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;

import javax.sql.DataSource;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.lang3.StringUtils;

import com.mysql.jdbc.jdbc2.optional.MysqlDataSource;

public class RuianImport {
	
	private Properties properties = null;
	private DataSource dataSource = null;
	private Connection connection = null;
	private CSVFormat csvFormat = null;
	
	private Map<String, Integer> zipCodes = null;
	private Map<String, Integer> cities = null;
	private Map<String, Integer> cityParts = null;
	private Map<String, Integer> streets = null;
	private Map<String, AtomicInteger> streetMap = null;
	private AtomicInteger objectCounter = null;
	
	private List<String> dataTables;
	private List<String> tempTables;
	
	private static final String SQLTABLE_PLACES = "cz_places";
	private static final String SQLTABLE_CITY_PARTS = "cz_city_parts";
	private static final String SQLTABLE_CITIES = "cz_cities";
	private static final String SQLTABLE_ZIP = "cz_zip";
	private static final String SQLTABLE_STREETS = "cz_streets";
	private static final String SQLTABLE_RELATIONS = "cz_relations";
	
	private static final String SQLTABLE_PLACES_TEMP = "cz_places_temp";
	private static final String SQLTABLE_CITY_PARTS_TEMP = "cz_city_parts_temp";
	private static final String SQLTABLE_CITIES_TEMP = "cz_cities_temp";
	private static final String SQLTABLE_ZIP_TEMP = "cz_zip_temp";
	private static final String SQLTABLE_STREETS_TEMP = "cz_streets_temp";
	private static final String SQLTABLE_RELATIONS_TEMP = "cz_relations_temp";
	
	public void run(String[] args) {
		try {
			dataTables = Arrays.asList(
					SQLTABLE_PLACES, SQLTABLE_CITY_PARTS,
					SQLTABLE_CITIES, SQLTABLE_ZIP,
					SQLTABLE_STREETS, SQLTABLE_RELATIONS);
			
			tempTables = Arrays.asList(
					SQLTABLE_PLACES_TEMP, SQLTABLE_CITY_PARTS_TEMP,
					SQLTABLE_CITIES_TEMP, SQLTABLE_ZIP_TEMP,
					SQLTABLE_STREETS_TEMP, SQLTABLE_RELATIONS_TEMP);
			
			File configFile = getConfigFile();
			loadProperties(configFile);
			dataSource = getDataSource();
			
			try {
				connection = dataSource.getConnection();
				
				if (StringUtils.isBlank(properties.getProperty("ruian.folder")))
					throw new IllegalStateException("Property ruian.folder not defined.");
				
				Path dataFolder = FileSystems.getDefault().getPath(properties.getProperty("ruian.folder"));
				if (!Files.isDirectory(dataFolder))
					throw new IllegalStateException(dataFolder + " is not directory.");
				
				zipCodes = new HashMap<String, Integer>();
				cities = new HashMap<String, Integer>();
				cityParts = new HashMap<String, Integer>();
				streets = new HashMap<String, Integer>();
				streetMap = new HashMap<String, AtomicInteger>();
				objectCounter = new AtomicInteger();
				
				csvFormat = CSVFormat.DEFAULT
						.withDelimiter(';')
						.withHeader()
						.withSkipHeaderRecord(false);
				
				makeTempTables();
				
				try (DirectoryStream<Path> ds = Files.newDirectoryStream(dataFolder)) {
					// Files.newDirectoryStream umi filtrovat obsah adresaru, ale nevim,
					// jak je to rozlisovanim velkych a malych pismen. Omezeni
					// na CSV resim ve streamu.
					StreamSupport.stream(ds.spliterator(), false)
							.filter(p -> StringUtils.endsWithIgnoreCase(p.normalize().toString(), ".csv"))
							.sorted((p1, p2) -> p1.compareTo(p2))
							.forEach(p -> importCsvFile(p.normalize()));
				}
				
				tempTablesToProduction();
			} finally {
				try { if (connection != null) connection.close(); } catch (Exception x) {}
			}
			
		} catch (Exception x) {
			x.printStackTrace();
		}
	}
	
	protected void importCsvFile(Path csvFile) {
		try {
			// PDY: 20141231_OB_537683_ADR.csv
			//if (!StringUtils.equalsIgnoreCase(csvFile.getFileName().toString(), "20161130_OB_537683_ADR.csv"))
			//	return;
			
			// Kozusany - Tazaly: 20141231_OB_503304_ADR.csv
			//if (!StringUtils.equalsIgnoreCase(csvFile.getFileName().toString(), "20150331_OB_503304_ADR.csv"))
			//	return;
			
			// Horka nad Moravou: 20141231_OB_502545_ADR.csv
			//if (!StringUtils.equalsIgnoreCase(csvFile.getFileName().toString(), "20150331_OB_502545_ADR.csv"))
			//	return;
			
			// Praha: 20141231_OB_554782_ADR.csv
			//if (!StringUtils.equalsIgnoreCase(csvFile.getFileName().toString(), "20150331_OB_554782_ADR.csv"))
			//	return;
			
			// Hledani:
			// select * from ulice where match(ft) against('+TIS*' IN BOOLEAN MODE) order by ulice collate utf8_czech_ci
			// select * from obce where match(ft) against('+A*' IN BOOLEAN MODE) order by obec collate utf8_czech_ci
			
			//System.out.println(csvFile + " - " + csvFile.getFileName().toString());
			System.out.println(csvFile);
			
			buildStreetMap(csvFile);
			
			try (CSVParser csvParser = CSVParser.parse(csvFile.toFile(), Charset.forName("cp1250"), csvFormat)) {
				//
				//int aaa = 0;
				for (CSVRecord record : csvParser) {
					importRecord(record);
					//if (++aaa == 1)
					//	break;
				}
				
			}
		} catch (Exception x) {
			// http://stackoverflow.com/questions/23548589/java-8-how-do-i-work-with-exception-throwing-methods-in-streams
			throw new RuntimeException(x);
		}
	}
	
	protected void buildStreetMap(Path csvFile) throws IOException {
		streetMap.clear();
		
		try (CSVParser csvParser = CSVParser.parse(csvFile.toFile(), Charset.forName("cp1250"), csvFormat)) {
			for (CSVRecord record : csvParser) {
				//
				String street = getCsvColumn(record, "Název ulice");
				
				if (StringUtils.isBlank(street))
					street = getCsvColumn(record, "Název části obce");
				
				if (StringUtils.isBlank(street))
					street = getCsvColumn(record, "Název MOMC");
				
				if (StringUtils.isBlank(street))
					street = getCsvColumn(record, "Název obce");
				
				String houseNumber = getCsvColumn(record, "Číslo domovní");
				
				if (StringUtils.isNotBlank(street) && StringUtils.isNotBlank(houseNumber)) {
					String key = street + "/" + houseNumber;
					
					String oriNumber = getCsvColumn(record, "Číslo orientační") + getCsvColumn(record, "Znak čísla orientačního");
					key += oriNumber;
					key = key.toUpperCase();
					
					AtomicInteger cnt = streetMap.get(key);
					
					if (cnt == null)
						streetMap.put(key, new AtomicInteger(1));
					else
						cnt.incrementAndGet();
				}
			}
		}
		
		/*
		System.out.println(streetMap.size());
		for (String key : streetMap.keySet()) {
			AtomicInteger cnt = streetMap.get(key);
			if (cnt.get() > 1)
				System.out.println(key);
		}
		*/
	}
	
	protected void importRecord(CSVRecord record) throws SQLException {
		//CSVRecord record
		//System.out.println(record.get("PSČ"));
		
		int placeId = Integer.parseInt(getCsvColumn(record, "Kód ADM"));
		String zipCode = getCsvColumn(record, "PSČ").replaceAll("\\s+", "");
		String city = getCsvColumn(record, "Název obce");
		String cityPart = getCsvColumn(record, "Název části obce");
		String street = getCsvColumn(record, "Název ulice");
		String cityCode = getCsvColumn(record, "Kód obce");
		
		Map<String, Object> data = new HashMap<String, Object>();
		Set<Integer> objectIds = new LinkedHashSet<Integer>();
		int zipCodeId = -1;
		int cityId = -1;
		int cityPartId = -1;
		int streetId = -1;
		String cityExpanded = null;
		
		// Ulozeni PSC.
		String str = zipCode.toUpperCase();
		String ft;
		if (StringUtils.isNotBlank(zipCode)) {
			if (!zipCodes.containsKey(str)) {
				zipCodeId = objectCounter.incrementAndGet();
				zipCodes.put(str, zipCodeId);
				ft = prepareForFulltext(str);
				
				data.clear();
				data.put("ID", zipCodeId);
				data.put("ZIP", zipCode);
				data.put("FT", ft);
				insertRecord(SQLTABLE_ZIP_TEMP, data);
			} else {
				zipCodeId = zipCodes.get(str);
			}
		}
		
		// Vazba na PSC
		if (zipCodeId != -1)
			objectIds.add(zipCodeId);
		
		// Obec.
		str = city.toUpperCase();
		if (StringUtils.isNotBlank(city)) {
			if (!cities.containsKey(str)) {
				cityId = objectCounter.incrementAndGet();
				cities.put(str, cityId);
				ft = prepareForFulltext(str);
				
				data.clear();
				data.put("ID", cityId);
				data.put("CITY", city);
				data.put("FT", ft);
				insertRecord(SQLTABLE_CITIES_TEMP, data);
			} else {
				cityId = cities.get(str);
			}
		}
		
		if (cityId != -1)
			objectIds.add(cityId);
		
		// Cast obce.
		// - ulozi se jen, kdyz cast obce obsahuje slovo, ktere neni
		//   obsazeno v nazvu obce.
		str = city + " - " + cityPart;
		str = str.toUpperCase();
		//System.out.println(str);
		if (StringUtils.isNotBlank(cityPart)) {
			if (isValidCityPart(city, cityPart)) {
				if (!cityParts.containsKey(str)) {
					cityPartId = objectCounter.incrementAndGet();
					cityParts.put(str, cityPartId);
					str = city + " - " + cityPart;
					ft = prepareForFulltext(cityPart);
					
					data.clear();
					data.put("ID", cityPartId);
					data.put("CITY_PART", str);
					data.put("FT", ft);
					insertRecord(SQLTABLE_CITY_PARTS_TEMP, data);
				} else {
					cityPartId = cityParts.get(str);
				}
			}
		}
		
		if (cityPartId != -1)
			objectIds.add(cityPartId);
				
		// Ulice.
		int priority = 1;
		str = street;
		if (StringUtils.isBlank(str)) {
			str = cityPart;
			priority++;
		}
		if (StringUtils.isBlank(str)) {
			str = city;
			priority++;
		}
		
		if (StringUtils.isNotBlank(str)) {
			if (!streets.containsKey(str.toUpperCase())) {
				streetId = objectCounter.incrementAndGet();
				streets.put(str.toUpperCase(), streetId);
				ft = prepareForFulltext(str);
				
				data.clear();
				data.put("ID", streetId);
				data.put("STREET", str);
				data.put("FT", ft);
				data.put("PRIORITY", priority);
				insertRecord(SQLTABLE_STREETS_TEMP, data);
			} else {
				streetId = streets.get(str.toUpperCase());
			}
		}
		
		if (streetId != -1)
			objectIds.add(streetId);
		
		// Adresni misto.
		String streetMapKey = street;
		
		if (StringUtils.isBlank(streetMapKey))
			streetMapKey = cityPart;
		
		if (StringUtils.isBlank(streetMapKey))
			streetMapKey = getCsvColumn(record, "Název MOMC");
		
		if (StringUtils.isBlank(streetMapKey))
			streetMapKey = city;
		
		String houseNumber = getCsvColumn(record, "Číslo domovní");
		if (StringUtils.isNotBlank(houseNumber))
			streetMapKey += "/" + houseNumber;
		
		String oriNumber = getCsvColumn(record, "Číslo orientační") + getCsvColumn(record, "Znak čísla orientačního");
		streetMapKey += oriNumber;
		streetMapKey = streetMapKey.toUpperCase();
		
		AtomicInteger streetCnt = streetMap.get(streetMapKey);
		boolean storePlaceType = streetCnt != null && streetCnt.get() > 1;
		if (StringUtils.equalsIgnoreCase(getCsvColumn(record, "Typ SO"), "č.ev."))
			storePlaceType = true;
		
		// Vyjimka pro Prahu
		if (StringUtils.equals(cityCode, "554782")) {
			/*
			if (StringUtils.isNotBlank(getCsvColumn(record, "Název MOP")) && StringUtils.isNotBlank(cityPart)) {
				//city = getCsvColumn(record, "Název MOP") + " - " + cityPart;
				cityExpanded = getCsvColumn(record, "Název MOP") + " - " + cityPart;
			}
			*/
			if (StringUtils.isNotBlank(getCsvColumn(record, "Název MOMC")) && StringUtils.isNotBlank(cityPart)) {
				//city = getCsvColumn(record, "Název MOP") + " - " + cityPart;
				cityExpanded = getCsvColumn(record, "Název MOMC");
				
				if (StringUtils.isNotBlank(cityExpanded) && isValidCityPart(cityExpanded, cityPart)) {
					cityExpanded += " - " + cityPart;
				}
			}
			
		}
		
		String placeAddress = street;
		
		if (StringUtils.isBlank(placeAddress))
			placeAddress = cityPart;
		
		if (StringUtils.isBlank(placeAddress))
			placeAddress = getCsvColumn(record, "Název MOMC");
		
		if (StringUtils.isBlank(placeAddress)) {
			if (StringUtils.isNotBlank(cityExpanded))
				placeAddress = cityExpanded;
			else
				placeAddress = city;
		}
		
		if (StringUtils.isNotBlank(placeAddress)) {
			data.clear();
			
			ft = prepareForFulltext(placeAddress);
			
			if (storePlaceType) {
				String placeType = getCsvColumn(record, "Typ SO");
				if (placeType.equalsIgnoreCase("č.ev."))
					placeType = "ev.č.";
				
				if (StringUtils.isNotBlank(placeType))
					placeAddress += " " + placeType;
			}
			
			if (StringUtils.isNotBlank(houseNumber)) {
				placeAddress += " " + houseNumber;
				ft += " " + prepareForFulltext(houseNumber);
			}
			
			// Cislo orientacni.
			//
			if (StringUtils.isNotBlank(oriNumber)) {
				if (StringUtils.isBlank(houseNumber))
					placeAddress += " " + oriNumber;
				else
					placeAddress += "/" + oriNumber;
				
				str = oriNumber.toUpperCase();
				while (str.length() < 4)
					str += "_";
				
				ft += " " + str;
				
				/*
				// Orientaci cislo do FT, pokud je prazdne cislo domovni.
				if (StringUtils.isBlank(houseNumber)) {
					str = oriNumber.toUpperCase();
					while (str.length() < 4)
						str += "_";
					
					ft += " " + str;
				} else {
					str = houseNumber + "_" + oriNumber;
					
					while (str.length() < 4)
						str += "_";
					
					ft += " " + str;
				}
				*/
			}
			
			String ftStreet = ft;
			String ftCity = StringUtils.EMPTY;
			
			// Doplneni casti obce do FT.
			if (StringUtils.isNotBlank(cityPart) && isValidCityPart(city, cityPart)) {
				ft += " " + prepareForFulltext(cityPart);
				ftCity += " " + prepareForFulltext(cityPart);
			}
			
			// Doplneni obce do FT.
			if (StringUtils.isNotBlank(city)) {
				ft += " " + prepareForFulltext(city);
				ftCity += " " + prepareForFulltext(city);
			}
			
			Set<String> fts = new LinkedHashSet<String>();
			Arrays.stream(ft.split("\\s+"))
				.distinct()
				.forEach(x -> fts.add(x));
			ft = StringUtils.join(fts, " ").trim();
			
			fts.clear();
			Arrays.stream(ftStreet.split("\\s+"))
				.distinct()
				.forEach(x -> fts.add(x));
			ftStreet = StringUtils.join(fts, " ").trim();
			
			fts.clear();
			Arrays.stream(ftCity.split("\\s+"))
				.distinct()
				.forEach(x -> fts.add(x));
			ftCity = StringUtils.join(fts, " ").trim();
			
			// GPS souradnice.
			String X = getCsvColumn(record, "Souřadnice X");
			String Y = getCsvColumn(record, "Souřadnice Y");
			if (StringUtils.isNotBlank(X) && StringUtils.isNotBlank(Y)) {
				/*
				if (StringUtils.equals(getCsvColumn(record, "Kód ADM"), "18324754")) {
					GeoPoint point = GeoPoint.jtskToWGS84(X, Y);
					System.out.println(point);
				}
				//*/
				
				GeoPoint point = GeoPoint.jtskToWGS84(X, Y);
				data.put("LON", point.getLongitude());
				data.put("LAT", point.getLatitude());
				
			}
			
			data.put("ID", placeId);
			data.put("STREET1", placeAddress);
			data.put("STREET2", "");
			data.put("CITY", StringUtils.isNotBlank(cityExpanded) ? cityExpanded : city);
			data.put("ZIP", zipCode);
			data.put("FT", ft);
			data.put("FT_STREET", ftStreet);
			data.put("FT_CITY", ftCity);
			
			insertRecord(SQLTABLE_PLACES_TEMP, data);
			
			// Doplneni vazeb.
			insertRelations(placeId, objectIds);
			/*
			for (int id : objectIds) {
				data.clear();
				data.put("OBJ_ID", id);
				data.put("ADR_ID", placeId);
				insertRecord("vazby", data);
			}
			*/
		}
	}
	
	protected boolean isValidCityPart(String city, String cityPart) {
		boolean isValid = false;
		city = alphanum(city.toUpperCase());
		cityPart = alphanum(cityPart.toUpperCase());
		
		List<String> listCity = Arrays.asList(words(city));
		List<String> listParts = Arrays.asList(words(cityPart));
		
		if (!listCity.isEmpty() && !listParts.isEmpty()) {
			// Shoda 1. slova.
			if (StringUtils.equalsIgnoreCase(listCity.get(0), listParts.get(0)))
				return false;
		}
		
		Set<String> setCity = new HashSet<String>(listCity);
		Set<String> setParts = new HashSet<String>(listParts);
		
		for (String s : setParts) {
			if (!setCity.contains(s)) {
				isValid = true;
				break;
			}
		}
		
		return isValid;
	}
	
	protected void insertRelations(int addressId, Set<Integer> objectIds) throws SQLException {
		String query = "INSERT INTO `" + SQLTABLE_RELATIONS_TEMP + "` SET `PLACE_ID` = ?, `OBJ_ID` = ?";
		try (PreparedStatement pst = connection.prepareStatement(query)) {
			pst.setInt(1, addressId);
			for (int objectId : objectIds) {
				pst.setInt(2, objectId);
				pst.executeUpdate();
			}
		}
	}
	
	protected void insertRecord(String table, Map<String, Object> data) throws SQLException {
		StringBuilder query = new StringBuilder("INSERT INTO `");
		query.append(table);
		query.append("` SET");
		
		int counter = 0;
		for (String key : data.keySet()) {
			query.append(" `");
			query.append(key);
			query.append("` = ?");
			if (++counter < data.size())
				query.append(",");
		}
		
		try (PreparedStatement pst = connection.prepareStatement(query.toString())) {
			counter = 1;
			for (String key : data.keySet()) {
				//String val = data.get(key);
				Object val = data.get(key);
				
				if (val == null)
					pst.setNull(counter, java.sql.Types.NULL);
				else if (val instanceof Integer)
					pst.setInt(counter, (int) val);
				else if (val instanceof Long)
					pst.setLong(counter, (long) val);
				else if (val instanceof BigDecimal)
					pst.setBigDecimal(counter, (BigDecimal) val);
				else if (val instanceof Double)
					pst.setDouble(counter, (double) val);
				else if (val instanceof Float)
					pst.setFloat(counter, (float) val);
				else if (val instanceof Timestamp)
					pst.setTimestamp(counter, (Timestamp) val);
				else if (val instanceof Calendar)
					pst.setTimestamp(counter, new Timestamp(((Calendar) val).getTimeInMillis()));
				else if (val instanceof String)
					pst.setString(counter, (String) val);
				else
					throw new IllegalStateException("Unsupported data type: " + val.getClass().getName());
				
				counter++;
			}
			
			pst.executeUpdate();
		}
	}
	
	protected String getCsvColumn(CSVRecord record, String column) {
		String val = StringUtils.trimToEmpty(record.get(column));
		return val;
	}
	
	protected String unaccent(String s) {
		String temp = Normalizer.normalize(s, Normalizer.Form.NFD);
		return temp.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
		//return temp.replaceAll("[^\\p{ASCII}]", "");
	}
	
	protected String alphanum(String s) {
		return s.replaceAll("[^\\p{L}\\p{N}]", " ");
	}
	
	protected String[] words(String s) {
		return s.split("\\s+");
	}
	
	protected String prepareForFulltext(String s) {
		s = unaccent(s);
		s = alphanum(s);
		String[] parts = s.split("(?<=\\d)(?=\\p{L})|(?<=\\p{L})(?=\\d)");
		s = StringUtils.join(parts, " ");
		parts = words(s);
		
		Set<String> set = new LinkedHashSet<String>();
		
		for (String part : parts) {
			String temp = StringUtils.trimToEmpty(part.toUpperCase());
			
			if (StringUtils.isNotBlank(temp)) {
				while (temp.length() < 4)
					temp += "_";
				
				set.add(temp);
			}
		}
		
		s = StringUtils.join(set, " ");
		
		return s;
	}
	
	protected DataSource getDataSource() {
		MysqlDataSource ds = new MysqlDataSource();
		ds.setURL(properties.getProperty("datasource.url"));
		ds.setUser(properties.getProperty("datasource.username"));
		ds.setPassword(properties.getProperty("datasource.password"));
		
		return ds;
	}
	
	protected File getConfigFile() {
		File config = new File(System.getProperty("user.dir"), "app.ini");
		return config;
	}
	
	protected void loadProperties(File configFile) throws IOException {
		try (FileReader reader = new FileReader(configFile)) {
			properties = new Properties();
			properties.load(reader);
		}
	}
	
	protected void makeTempTables() throws SQLException {
		try (Statement stmt = connection.createStatement()) {
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < tempTables.size(); i++) {
				sb.setLength(0);
				sb.append("CREATE TABLE `");
				sb.append(tempTables.get(i));
				sb.append("` LIKE `");
				sb.append(dataTables.get(i));
				sb.append("`;");
				stmt.executeUpdate(sb.toString());
			}
		}
	}
	
	protected void tempTablesToProduction() throws SQLException {
		try (Statement stmt = connection.createStatement()) {
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < dataTables.size(); i++) {
				sb.setLength(0);
				sb.append("DROP TABLE `");
				sb.append(dataTables.get(i));
				sb.append("`;");
				stmt.executeUpdate(sb.toString());
				sb.setLength(0);
				sb.append("RENAME TABLE `");
				sb.append(tempTables.get(i));
				sb.append("` TO `");
				sb.append(dataTables.get(i));
				sb.append("`;");
				stmt.executeUpdate(sb.toString());
			}
		}
	}
}
