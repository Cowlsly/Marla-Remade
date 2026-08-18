package com.vayunmathur.library.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Attractions
import androidx.compose.material.icons.filled.BakeryDining
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Chair
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.Church
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Hardware
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.LocalBar
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalGroceryStore
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.LocalLaundryService
import androidx.compose.material.icons.filled.LocalLibrary
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.LocalPolice
import androidx.compose.material.icons.filled.LocalPostOffice
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Museum
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material.icons.filled.Toys
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Curated Material [ImageVector]s for the map's OSM POI categories, exposed here
 * (the one module allowed to touch `androidx.compose.material.icons`) so the maps
 * app can rasterize them into MapLibre marker bitmaps without importing Material
 * icons directly.
 *
 * [of] maps the numeric POI `type` used by the maps app's `PoiCategories`
 * (0..50, 255 = other) to an icon; keep this in sync with that table (never
 * renumber, only append).
 */
object MapsPoiVectors {
    fun of(type: Int): ImageVector = when (type) {
        0, 2 -> Icons.Filled.Restaurant       // restaurant / fast food
        1 -> Icons.Filled.LocalCafe           // cafe
        3, 46 -> Icons.Filled.LocalBar        // bar / liquor
        4 -> Icons.Filled.ShoppingBag         // shop
        5 -> Icons.Filled.LocalGroceryStore   // grocery
        6 -> Icons.Filled.LocalGasStation     // gas station
        7 -> Icons.Filled.LocalPharmacy       // pharmacy
        8 -> Icons.Filled.Hotel               // hotel
        9 -> Icons.Filled.AccountBalance      // bank
        10 -> Icons.Filled.LocalHospital      // hospital
        11 -> Icons.Filled.School             // school
        12 -> Icons.Filled.Park               // park
        13 -> Icons.Filled.FitnessCenter      // gym
        14 -> Icons.Filled.Church             // place of worship
        15, 40 -> Icons.Filled.Attractions    // attraction / tourist info
        16 -> Icons.Filled.LocalParking       // parking
        17 -> Icons.Filled.Theaters           // cinema
        18 -> Icons.Filled.Theaters           // theatre
        19 -> Icons.Filled.LocalLibrary       // library
        20 -> Icons.Filled.LocalPostOffice    // post office
        21 -> Icons.Filled.LocalPolice        // police
        22 -> Icons.Filled.LocalFireDepartment // fire station
        23 -> Icons.Filled.AccountBalance     // town hall
        24 -> Icons.Filled.Checkroom          // clothing
        25 -> Icons.Filled.Devices            // electronics
        26 -> Icons.Filled.Hardware           // hardware
        27 -> Icons.Filled.ContentCut         // beauty
        28 -> Icons.Filled.DirectionsCar      // car
        29 -> Icons.Filled.BakeryDining       // bakery
        30 -> Icons.Filled.MenuBook           // books
        31 -> Icons.Filled.Chair              // furniture
        32 -> Icons.Filled.SportsSoccer       // sports
        33 -> Icons.Filled.Storefront         // department store
        34, 35 -> Icons.Filled.MedicalServices // dentist / doctor
        36, 45 -> Icons.Filled.Pets           // veterinary / pet
        37 -> Icons.Filled.EvStation          // charging station
        38 -> Icons.Filled.Museum             // museum
        39 -> Icons.Filled.Work               // office
        41 -> Icons.Filled.LocalFlorist       // florist
        42 -> Icons.Filled.Diamond            // jewelry
        43 -> Icons.Filled.Visibility         // optician
        44 -> Icons.Filled.LocalLaundryService // laundry
        47 -> Icons.Filled.Toys               // toys
        48 -> Icons.Filled.CardGiftcard       // gift
        49 -> Icons.Filled.Storefront         // marketplace
        50 -> Icons.Filled.Train              // train / transit station
        else -> Icons.Filled.Place            // other / fallback
    }
}
