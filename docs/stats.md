# Stats

**Stats** in the header of the photos screen shows your whole library in numbers: an overview, then your
photos by year, month, day of the week and hour, then the people in them, your own tags, the things in
them, the countries, the cities and the cameras. Every section is a chart with the full table under it.

## Where the numbers come from

From the phone's own copy of your index, never from Opensolr. Opening Stats makes **no request at all**:
the numbers are six grouped queries on the phone's database, each answered from an index of that
database, so they come back at once on a library of any size. They are worked out again every time the
screen opens, so a sync that just ran is already counted.

While the phone is still reading its copy of the index (right after an install or a reset), a note at the
top says the numbers are still growing.

| Section | What is counted |
|---|---|
| **Overview** | Photos, their size on the phone, and how many are tagged, have people, have a place, have text read out of them, are not read yet, or have no date |
| **Photos per year** | One column per year. The year is counted the way the index counts it, so a year opens exactly the photos it counted |
| **By month** | January to December, across every year: when you take the most photos |
| **By day of the week** | Starting on the first day of the week of your language |
| **By hour of the day** | 0 to 23, in the phone's time zone, like the date headings of the grid |
| **People** | Every person named on your photos |
| **My tags** | Every tag you gave a photo |
| **Things** | The words the photos were read into (`labels`). On a plan without vector search no photo is read, so this section does not appear |
| **Countries** and **Cities** | Where the photos were taken |
| **Cameras** | Make and model, as one name |

## Charts and tables

Years, months, days and hours are column charts; a label under a column is drawn only where it fits, so
none ever runs into another. People, tags, things, places and cameras are bar charts of the ten biggest.
Under every chart is a table with the name, the number of photos and their share of the library. Long
tables show ten lines and a **Show all**; Things lists its 50 biggest and stops there.

## Every line opens its photos

A line that names something the index can filter by (a year, a person, a tag, a thing, a country, a city, a
camera) has an arrow: tap it, or its bar, and the photos grid opens with that filter alone. **Back** brings
you to Stats exactly where you were. Months, days and hours have no filter of their own, so they only
count.

## Folding

Every section starts folded. A tap on its heading opens or folds it, and the button at the top opens all of
them or folds them all. Which sections are open is remembered for good; where you were in the list lasts for
as long as the app runs.

## What changed in the phone's copy for it

To count the things in your photos on the phone, the copy keeps the words each photo was read into beside
its tags and names, and the camera model in a column of its own. An update fills both once from what the
phone already stores, a page at a time, without asking the index anything.
